package ai.snaphop.geon.index;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import ai.snaphop.geon.AddressType;
import ai.snaphop.geon.BBox;
import ai.snaphop.geon.PlaceFilter;

/// A [PlaceFilter] prepared for fast checks against doc records.
final class PreparedFilter {

	final PlaceFilter filter;

	final int[] countries;

	final EnumSet<AddressType> layers;

	final int[][] includeIds;

	final int[][] excludeIds;

	private final Terms terms;

	private final DocCodec.Reader docs;

	PreparedFilter(PlaceFilter filter, Terms terms, DocCodec.Reader docs) {
		this.filter = filter;
		this.terms = terms;
		this.docs = docs;
		this.countries = filter.countryCodes().stream().mapToInt(DocCodec::packCountry).toArray();
		this.layers = filter.layers().isEmpty() ? EnumSet.noneOf(AddressType.class) : EnumSet.copyOf(filter.layers());
		this.includeIds = categoryIds(filter.includeCategories());
		this.excludeIds = categoryIds(filter.excludeCategories());
	}

	private int[][] categoryIds(List<Set<String>> groups) {
		int[][] out = new int[groups.size()][];
		for (int g = 0; g < out.length; g++) {
			out[g] = groups.get(g).stream()
				.mapToInt(c -> terms.find(Terms.utf8(Field.CATEGORY_PREFIX + c)))
				.toArray();
		}
		return out;
	}

	boolean passesFixed(int doc) {
		if (countries.length > 0) {
			int packed = docs.packedCountry(doc);
			boolean ok = false;
			for (int c : countries) {
				ok |= c == packed;
			}
			if (!ok) {
				return false;
			}
		}
		if (!layers.isEmpty() && !layers.contains(docs.addressType(doc))) {
			return false;
		}
		BBox bbox = filter.bbox();
		if (bbox != null && !bbox.contains(docs.lon(doc), docs.lat(doc))) {
			return false;
		}
		return filter.tagFilters().isEmpty() || filter.testTags(docs.osmKey(doc), docs.osmValue(doc));
	}

	boolean passesCategories(Forward.Entries e) {
		for (int[] group : includeIds) {
			boolean any = false;
			for (int id : group) {
				any |= id >= 0 && e.mask(id) != 0;
			}
			if (!any) {
				return false;
			}
		}
		for (int[] group : excludeIds) {
			boolean all = group.length > 0;
			for (int id : group) {
				all &= id >= 0 && e.mask(id) != 0;
			}
			if (all) {
				return false;
			}
		}
		return true;
	}

}
