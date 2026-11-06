package ai.snaphop.geon.dump;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import ai.snaphop.geon.Place;
import io.avaje.json.JsonException;
import io.avaje.json.JsonReader;
import io.avaje.json.stream.JsonStream;

/// Reads a Nominatim dump file and hands each [Place] to a consumer.
///
/// The input is JSON lines: one document per line, as Photon and Nominatim write
/// dumps. The reader streams line by line, so memory use does not grow with the size of
/// the file.
///
/// The input must be uncompressed. Dumps are usually distributed compressed with
/// zstd; decompress them on the fly, for example:
///
/// ```
/// zstd -dc photon-dump-andorra-1.0-latest.jsonl.zst | geon import -
/// ```
///
/// A reader is immutable and thread safe; each [#read(InputStream, Consumer)] call
/// has its own state.
public final class DumpReader {

	/// The dump format version this reader understands.
	public static final String FORMAT_VERSION = "0.1.0";

	/// The languages Photon imports by default.
	public static final List<String> DEFAULT_LANGUAGES = List.of("en", "de", "fr", "it");

	private static final System.Logger logger = System.getLogger(DumpReader.class.getName());

	private static final JsonStream JSON = JsonStream.builder().build();

	private final Set<String> languages;

	private final ExtraTags extraTags;

	private final boolean geometries;

	private final Set<String> countryCodes;

	private DumpReader(Builder b) {
		this.languages = Set.copyOf(b.languages);
		this.extraTags = b.extraTags;
		this.geometries = b.geometries;
		this.countryCodes = Set.copyOf(b.countryCodes);
	}

	/// Starts configuring a reader.
	/// @return a builder with Photon's defaults.
	public static Builder builder() {
		return new Builder();
	}

	/// Reads a whole dump.
	///
	/// @param input the uncompressed dump. It is not closed.
	/// @param sink receives each place in file order.
	/// @return what was read.
	/// @throws DumpFormatException if the input is not a valid dump. The message says
	///   where the problem is.
	/// @throws IOException if reading fails.
	public DumpSummary read(InputStream input, Consumer<Place> sink) throws IOException {
		return new Run(sink).read(new Lines(input));
	}

	private final class Run {

		private JsonReader r = JSON.reader(new byte[0]);

		private boolean seenHeader;

		private final Consumer<Place> sink;

		private final PlaceAssembler assembler = new PlaceAssembler(languages);

		private @Nullable String generator;

		private @Nullable Instant dataTimestamp;

		private boolean sortedByCountry;

		private boolean hasAddressLines = true;

		private @Nullable String currentCountry;

		private long placeCount;

		private long skippedCount;

		Run(Consumer<Place> sink) {
			this.sink = sink;
		}

		/// Parses the input line by line. A line may hold several documents, but a
		/// document may not span lines, which is what the JSON lines format promises.
		DumpSummary read(Lines lines) throws IOException {
			byte[] line;
			while ((line = lines.next()) != null) {
				if (Lines.isBlank(line)) {
					continue;
				}
				try (JsonReader reader = JSON.reader(line)) {
					r = reader;
					r.beginStream();
					while (r.hasNextStreamElement()) {
						document();
					}
				}
				catch (JsonException e) {
					throw new DumpFormatException("Invalid JSON on line " + lines.number() + " of the dump: "
							+ e.getMessage() + ". Each document must be on a single line (JSON lines).", e);
				}
				catch (DumpFormatException e) {
					throw new DumpFormatException("Line " + lines.number() + " of the dump: " + e.getMessage(), e);
				}
			}
			if (!seenHeader) {
				throw new DumpFormatException("The dump is empty. Expected a NominatimDumpFile header first.");
			}
			return new DumpSummary(generator, dataTimestamp, placeCount, skippedCount);
		}

		private void document() throws DumpFormatException {
			String type = beginDocument();
			if (!seenHeader) {
				if (!type.equals("NominatimDumpFile")) {
					throw new DumpFormatException(
							"The first document must be of type 'NominatimDumpFile' but was '" + type + "'.");
				}
				readHeader();
				seenHeader = true;
				endDocument();
				return;
			}
			switch (type) {
				case "Place" -> readPlaces();
				case "CountryInfo" -> readCountryInfo();
				case "NominatimDumpFile" -> throw new DumpFormatException(
						"Found a second NominatimDumpFile header (" + r.location() + ").");
				default -> {
					logger.log(Level.WARNING, "Ignoring dump document of unknown type ''{0}''.", type);
					r.skipValue();
				}
			}
			endDocument();
		}

		/// Reads `{"type": ..., "content":` and leaves the reader at the content.
		private String beginDocument() throws DumpFormatException {
			r.beginObject();
			if (!r.hasNextField() || !r.nextField().equals("type")) {
				throw new DumpFormatException("Each document must start with a 'type' field (" + r.location() + ").");
			}
			String type = r.readString();
			while (r.hasNextField()) {
				if (r.nextField().equals("content")) {
					return type;
				}
				r.skipValue();
			}
			throw new DumpFormatException("Document of type '" + type + "' has no 'content' (" + r.location() + ").");
		}

		private void endDocument() {
			while (r.hasNextField()) {
				r.nextField();
				r.skipValue();
			}
			r.endObject();
		}

		private void readHeader() throws DumpFormatException {
			String version = null;
			r.beginObject();
			while (r.hasNextField()) {
				String field = r.nextField();
				if (r.isNullValue()) {
					continue;
				}
				switch (field) {
					case "version" -> version = r.readString();
					case "generator" -> generator = r.readString();
					case "data_timestamp" -> dataTimestamp = parseTimestamp(r.readString());
					case "features" -> readFeatures();
					default -> r.skipValue();
				}
			}
			r.endObject();
			if (!FORMAT_VERSION.equals(version)) {
				throw new DumpFormatException("Unsupported dump format version '" + version + "'. This reader supports '"
						+ FORMAT_VERSION + "'.");
			}
		}

		private void readFeatures() {
			r.beginObject();
			while (r.hasNextField()) {
				String field = r.nextField();
				if (r.isNullValue()) {
					continue;
				}
				switch (field) {
					case "sorted_by_country" -> sortedByCountry = r.readBoolean();
					case "has_addresslines" -> hasAddressLines = r.readBoolean();
					default -> r.skipValue();
				}
			}
			r.endObject();
		}

		private void readCountryInfo() throws DumpFormatException {
			if (r.currentToken() != JsonReader.Token.BEGIN_ARRAY) {
				throw new DumpFormatException("CountryInfo content must be an array (" + r.location() + ").");
			}
			r.beginArray();
			while (r.hasNextElement()) {
				String code = null;
				Map<String, String> names = Map.of();
				r.beginObject();
				while (r.hasNextField()) {
					String field = r.nextField();
					if (r.isNullValue()) {
						continue;
					}
					switch (field) {
						case "country_code" -> code = r.readString();
						case "name" -> names = Json.stringMap(r);
						default -> r.skipValue();
					}
				}
				r.endObject();
				if (code != null) {
					assembler.putCountry(code, names);
				}
			}
			r.endArray();
		}

		private void readPlaces() throws DumpFormatException {
			switch (r.currentToken()) {
				case BEGIN_OBJECT -> handle(RawPlace.parse(r, JSON, extraTags, geometries), true);
				case BEGIN_ARRAY -> {
					r.beginArray();
					while (r.hasNextElement()) {
						handle(RawPlace.parse(r, JSON, extraTags, geometries), false);
					}
					r.endArray();
				}
				default -> throw new DumpFormatException(
						"Place content must be an object or an array of objects (" + r.location() + ").");
			}
		}

		private void handle(RawPlace raw, boolean splitAddresses) {
			if (sortedByCountry && raw.countryCode != null && !raw.countryCode.equals(currentCountry)) {
				assembler.clearAddressLines();
				currentCountry = raw.countryCode;
			}
			if (hasAddressLines) {
				assembler.rememberAddressLine(raw);
			}
			if (!countryCodes.isEmpty() && (raw.countryCode == null
					|| !countryCodes.contains(raw.countryCode.toUpperCase(Locale.ROOT)))) {
				skippedCount++;
				return;
			}
			List<Place> places = assembler.assemble(raw, splitAddresses);
			if (places.isEmpty()) {
				skippedCount++;
			}
			for (Place p : places) {
				sink.accept(p);
				placeCount++;
			}
		}

		private @Nullable Instant parseTimestamp(String value) {
			// Generators write ISO 8601 with offsets such as "+00:00" or "+0000".
			for (var format : List.of(DateTimeFormatter.ISO_OFFSET_DATE_TIME,
					DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]XX"))) {
				try {
					return OffsetDateTime.parse(value, format).toInstant();
				}
				catch (DateTimeParseException e) {
					// try the next format
				}
			}
			logger.log(Level.WARNING, "Ignoring unparsable data_timestamp ''{0}'' in the dump header.", value);
			return null;
		}

	}

	/// Configures a [DumpReader]. The defaults match Photon's import defaults.
	public static final class Builder {

		private final Set<String> languages = new LinkedHashSet<>(DEFAULT_LANGUAGES);

		private ExtraTags extraTags = ExtraTags.NONE;

		private boolean geometries;

		private final Set<String> countryCodes = new LinkedHashSet<>();

		private Builder() {
		}

		/// The languages whose names to keep besides `default`. Defaults to
		/// [DumpReader#DEFAULT_LANGUAGES].
		/// @param languages language codes such as `en`.
		/// @return this.
		public Builder languages(Collection<String> languages) {
			this.languages.clear();
			this.languages.addAll(languages);
			return this;
		}

		/// Which extra tags to keep. Defaults to [ExtraTags#NONE].
		/// @param extraTags the setting.
		/// @return this.
		public Builder extraTags(ExtraTags extraTags) {
			this.extraTags = extraTags;
			return this;
		}

		/// Whether to keep full geometries. They make the index much larger. Defaults to `false`.
		/// @param geometries `true` to keep them.
		/// @return this.
		public Builder geometries(boolean geometries) {
			this.geometries = geometries;
			return this;
		}

		/// Only import places in these countries. Empty, the default, imports all.
		/// @param countryCodes ISO 3166-1 alpha-2 codes in any case.
		/// @return this.
		public Builder countryCodes(Collection<String> countryCodes) {
			this.countryCodes.clear();
			countryCodes.forEach(c -> this.countryCodes.add(c.toUpperCase(Locale.ROOT)));
			return this;
		}

		/// Builds the reader.
		/// @return the reader.
		public DumpReader build() {
			return new DumpReader(this);
		}

	}

}
