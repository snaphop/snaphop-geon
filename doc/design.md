# Geon Design

Status: **draft for review**. Nothing in the index section is implemented yet.

Geon is a geocoder for OpenStreetMap data. It serves a Photon compatible HTTP API
and is built as a GraalVM native image.

## Goals

1. Photon API compatibility: same endpoints, parameters, status codes, error messages
   and GeoJSON response shape.
2. Results at least as good as Photon. Exact ranking parity is a non goal.
3. Small footprint, fast startup, fast queries.
4. Few dependencies:
   * Rainbow Gum (logging)
   * avaje-jsonb (JSON)
   * Netty (HTTP)
   * JUnit 5 (tests only)
5. The index sits behind an interface so other implementations can be plugged in.

## What Photon does today

Photon is a thin layer over OpenSearch. Knowing what it does tells us what we can
throw away.

* **Storage:** one OpenSearch document per place. Names and address parts are copied
  into several analyzed "collector" fields (`collector.all`, `.all.ngram`, `.name`,
  `.name.prefix`, `.parent`, `.field.*`). A planet database is about 95 GB, and
  64 GB of RAM is recommended.
* **Forward search:** a large bool and function score query.
  1. It combines edge n-gram matches, fuzzy matches and house number matches.
  2. If nothing matches, it reruns in "lenient" mode with more fuzziness.
  3. The top 1.5 × limit hits are rescored in Java:
     * importance
     * location bias with exponential decay
     * `QueryReranker`, a string comparison of the query against the result's name and address
  4. Duplicate streets are removed.
* **Reverse:** a geo distance filter plus a sort by distance. There is an optional
  `query_string_filter` that passes raw Lucene query syntax through.
* **Input:** a Nominatim PostgreSQL database, or the Nominatim dump format (JSON lines,
  usually zstd compressed). The planet dump is about 27 GB compressed.

Most of Photon's final ranking already happens in plain Java after OpenSearch returns
candidates. OpenSearch is mostly used to find candidates.

## Modules

The Maven groupId is `ai.snaphop.geon`. Each module's main package matches its
Java module name.

| Module | Package | Purpose | Depends on |
|---|---|---|---|
| `geon` | `ai.snaphop.geon` | Place model, query model, `GeonIndex` interface | JDK only |
| `geon-dump` | `ai.snaphop.geon.dump` | Streaming reader for the Nominatim dump format | `geon`, avaje-jsonb |
| `geon-index` | `ai.snaphop.geon.index` | Default index: build and query | `geon` |
| `geon-http` | `ai.snaphop.geon.http` | Minimal Netty HTTP server and router | Netty |
| `geon-photon` | `ai.snaphop.geon.photon` | Photon API: parameter parsing, validation, GeoJSON output | `geon`, `geon-http` |
| `geon-api` | `ai.snaphop.geon.api` | Geon's own native API (later), designed for efficiency rather than compatibility | `geon`, `geon-http` |
| `geon-app` | `ai.snaphop.geon.app` | CLI (`import`, `serve`), wiring, native image | all of the above, Rainbow Gum |
| `test/geon-test-photon` | | Differential tests against a real Photon | JUnit |

### APIs are adapters

The Photon API is one adapter over the core query model in `geon`. It is there for
compatibility, not as the canonical API. Geon's own API (`geon-api`) can expose the
same engine more efficiently, for example:

* leaner responses (no repeated GeoJSON scaffolding, a stable result ID)
* batch queries
* explicit, typed parameters instead of Photon's string conventions

Nothing in `geon` or `geon-index` may depend on Photon types or conventions. Photon
specifics live only in `geon-photon`.

The Photon test fixtures we port are derived from Apache-2.0 code. They live only in
`test/geon-test-photon`, which carries its own `LICENSE` and `NOTICE`. The rest of
the project is 0BSD.

## Compatibility testing

Photon has no black box test suite. Its tests are JUnit tests that build `PhotonDoc`
objects in Java and use an embedded OpenSearch.

Geon will use a differential harness instead:

1. **Fixtures** are written in the Nominatim dump format:
   * small hand-written dumps ported from Photon's `Api*` and `Query*` tests
   * the real Andorra extract, downloaded at test time and not committed because it is ODbL data
2. The harness imports each fixture into a real `photon.jar` and into Geon, then
   sends the same requests to both.
3. Each case is an enum constant with a comparison mode:
   * `EXACT`: status, error messages, `/status`, reverse
   * `SHAPE`: same fields and types
   * `TOP`: the same top result, or a result Geon ranks better and that we have
     reviewed and accepted

## Index design

### Principles

* **Immutable and memory mapped.** `import` writes one index file. `serve` maps it
  with the FFM API (`MemorySegment`). Startup does no loading, so it is fast. The Java
  heap stays small and the OS page cache does the work. Native image supports FFM.
* **Build offline, query cheaply.** All the expensive work (normalization, sorting,
  interning, n-gram expansion) happens at import time.
* **Doc IDs follow importance.** Docs are numbered by descending importance. Postings
  in doc ID order are therefore also in importance order, so a query can stop early
  once it has enough good candidates. This is the main speed win.
* **Our own scoring.** Scoring is explicit, explainable and debuggable. There is no
  BM25; term statistics mean little for short place names.

### File layout

There is one file, made of sections:

1. **Header:** format version, import date, languages, feature flags.
2. **String pool:** every distinct display string stored once. Address strings are
   hugely repetitive. For example, every Andorra place carries about 40 translations
   of "Andorra la Vella". Interning them should be the single biggest size reduction.
3. **Address nodes:** each unique address component (city, state and so on, with all
   its localized names) is stored once. A place refers to its address parts by node
   ID, in the same spirit as the dump format's `addresslines`.
4. **Doc table:** fixed width records with variable length overflow:
   * osm type, osm id, osm key and value (dictionary encoded)
   * address type, importance, centroid, bbox
   * name refs, address node refs, house number, postcode, country code
   * categories, extra tags
5. **Term dictionary:** normalized tokens, sorted and front coded in blocks, with a
   block index. Supports:
   * exact lookup
   * prefix ranges (search as you type)
   * fuzzy lookup, by running a Levenshtein automaton over the sorted terms
   We may move to an FST later if size requires it.
6. **Postings:** for each term, the doc IDs (delta and varint encoded) and a one-byte
   field mask per doc. The mask records where the term occurs: name, street, locality,
   city, county, state, country, house number, postcode or category. One index replaces
   Photon's many collector fields.
7. **Geo index:** a packed Hilbert R-tree over centroids and bboxes, in flatbush
   style. It handles reverse k-nearest search and bbox filters.
8. **Geometries (optional):** compact encoded geometry, only when the import
   enables geometry support.

### Text normalization

* Unicode NFKD, then drop combining marks.
* A small fold table for letters that NFKD does not decompose: ß, ø, ł, æ, đ, þ and
  similar.
* Lowercase, then fold apostrophe variants the way Photon does.
* Tokenize on whitespace and punctuation. House numbers keep their alphanumeric
  shape, for example `12a` and `12 a`.
* No ICU dependency. If later quality work shows we need ICU, I will ask first.

### Forward search

1. **Parse the query** into tokens. The last token is a prefix unless the query ends
   with a space. That is Photon's search as you type behaviour.
2. **Expand each token** into a set of terms: exact, prefix range, then fuzzy within an
   edit distance of 1 or 2 depending on length. Each match kind carries a penalty.
   Synonyms and classification terms are also applied here.
3. **Generate candidates.** Intersect the postings in importance order, allowing one
   missing token when the query is long. That is the equivalent of Photon's lenient
   pass, but it is not a second query. Candidates must also pass the filters:
   * country code, layer, osm tag, category, bbox
   * these are checked against doc table columns, or category postings
4. **Score** with a single explicit formula:
   * Token coverage, weighted by the field that matched (from the field mask).
   * Match kind: exact > prefix > fuzzy.
   * A name match outranks an address-only match. For an address query, a house number
     and street match together outrank a partial match.
   * Plus importance, plus location bias (Photon's decay curve, so `zoom` and
     `location_bias_scale` keep their meaning).
5. **Remove street duplicates** with Photon's rules, apply the limit, then format.

`debug=1` returns the score breakdown for each result.

### Reverse

* Use the R-tree to find the nearest k docs within the radius, ordered by distance.
* Then apply the same filters as forward search.
* For `query_string_filter`, support a documented subset of the Lucene syntax:
  `field:value`, `AND`, `OR`, `NOT` and parentheses. Anything else returns a clear 400
  error instead of being silently ignored.

### Updates

* The first milestone is import only.
* Later, use LSM style updates: a small mutable delta index plus tombstones in front of
  the immutable base, merged periodically. The `GeonIndex` interface does not assume
  that the index is immutable.

### Memory and scale targets (to verify)

* The index file should be well under Photon's 95 GB for the planet. The interning and
  single index design should bring it to roughly a quarter of that, but this is a
  guess until we measure it on real extracts.
* The heap should be small (hundreds of MB) whatever the dataset size.
* Import uses bounded memory: sorted runs on disk plus a k-way merge, so planet scale
  does not need planet sized RAM.

## Deferred

* Import from a Nominatim PostgreSQL database. It needs a JDBC driver, which is a new
  dependency.
* `/nominatim-update` endpoints. They depend on the database import above.
* `/metrics`. A hand-written Prometheus text exposition is easy to add, so no
  Micrometer.
