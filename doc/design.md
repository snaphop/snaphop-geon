# Geon Design

Status: the index, the dump import, the Photon compatible API and the CLI are
implemented. Sections below say where the implementation differs from the first plan.
[Measurements](#measurements) has the current numbers against Photon.

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

One file of sections; `geon-index/.../Layout.java` documents the exact bytes.

1. **Header:** format version, then the offset and length of every section.
2. **Strings:** display strings stored once, referenced by offset. Numeric place
   IDs are stored as numbers instead.
3. **Address sets:** the address parts, context names, postcode and country code a
   place has, stored once and shared. Every house of a street in one town shares one
   set, so most places carry just a reference. This replaced the planned per part
   address nodes: sharing the whole set is simpler and saves more.
4. **Doc table:** a fixed 28 byte record per place (coordinates, importance, tag,
   country, address type, flags and one offset), plus a variable record with the
   place's own terms and fields.
5. **Term dictionary:** sorted, front coded blocks with a block index.
6. **Postings:** per term, delta encoded doc IDs, split into primary (the term is in
   the place's name, house number or, for addresses, street) and secondary.
7. **Term lists:** each place has a list of its own terms with a field mask, and each
   address set has one for its terms. A query merges the two to check candidates.
8. **Geo index:** a packed Hilbert R-tree; leaves are doc IDs whose coordinates come
   from the doc table.

### Import

`geon import` streams the dump: each place is encoded to a temporary spill file as
it is read. After sorting by importance, postings are collected in memory up to the
budget (`-import-memory`, or `GEON_IMPORT_MEMORY`; default a quarter of the heap),
written as sorted runs and merged. Memory then grows with the number of distinct
terms, strings and address sets, not with the number of places.

Run imports on HotSpot: the native image's serial garbage collector makes a
Switzerland import 5 times slower.

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

### Measurements

Switzerland and Liechtenstein, GraphHopper's 2026-10-31 dump, 3,145,827 places, on a
20 core machine. Query latency is over 500 sequential `/api` requests built from the
dump: names, typed prefixes and addresses.

| | Photon 1.3.0 | Geon (HotSpot) | Geon (native) |
|---|---|---|---|
| Import time | 140 s | 32 s | 155 s |
| Import peak memory | 9.5 GB | 3.0 GB (1 GB budget) | 2.0 GB |
| Index on disk | 1.3 GB | 438 MB | 438 MB |
| Serving memory (RSS) | 1.8 GB | 1.8 GB (default heap) | 0.44 GB |
| Startup | about 3 s | about 100 ms | 28 ms |
| `/api` median | 3.6 ms | 0.9 ms | 1.0 ms |
| `/api` p95 | 6.6 ms | 2.5 ms | 4.1 ms |
| `/api` p99 | 8.8 ms | 7 to 9 ms | 17 ms |

Against Photon's top result on the same queries, Geon returns the same place for 245
of 255 address queries. For name and prefix queries it differs more often, mostly with
another place of the same name or, by review, a closer match.

## Deferred

* Import from a Nominatim PostgreSQL database. It needs a JDBC driver, which is a new
  dependency.
* `/nominatim-update` endpoints. They depend on the database import above.
* `/metrics`. A hand-written Prometheus text exposition is easy to add, so no
  Micrometer.
