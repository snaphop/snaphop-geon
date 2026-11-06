# Geon

Geon is a geocoder for [OpenStreetMap](https://www.openstreetmap.org) data with a
[Photon](https://github.com/komoot/photon) compatible API. It aims to beat Photon on:

* footprint and startup time (one memory mapped index file, GraalVM native image)
* dependencies (Rainbow Gum, avaje-json and Netty)
* query speed and result quality
* documentation

**Status:** early work in progress. See [doc/design.md](doc/design.md).

## Build

Requires JDK 25 (GraalVM for native images).

```
./mvnw install                # build and run the end to end tests
./mvnw -Pphoton install       # also compare against a real Photon 1.3.0 (downloads it once)
./mvnw -Pnative -pl geon-app package   # GraalVM native executable geon-app/target/geon
```

## Run

Import a Photon dump, for example a country extract from
[GraphHopper's weekly dumps](https://download1.graphhopper.com/public/), then serve it.
Option names follow Photon's.

```
cd geon-app/target
zstd -dc photon-dump-andorra-1.0-latest.jsonl.zst \
  | java -cp "geon-app-0.1.0-SNAPSHOT.jar:lib/*" ai.snaphop.geon.app.Main import -import-file - -import-memory 1g
java --enable-native-access=ALL-UNNAMED -cp "geon-app-0.1.0-SNAPSHOT.jar:lib/*" ai.snaphop.geon.app.Main serve
curl 'http://localhost:2322/api?q=andorra'
```

Import on the JVM (HotSpot): it is several times faster than the native image there.
`-import-memory` (or `GEON_IMPORT_MEMORY`) bounds the memory used for postings; the
rest spills to temporary files. Serve with either; the native image starts in about
30 ms and uses far less memory, HotSpot has the lower tail latency. See
[doc/design.md](doc/design.md#measurements) for numbers against Photon.

## Modules

| Module | Purpose |
|---|---|
| `geon` | Place and query model, the `GeonIndex` interface |
| `geon-dump` | Streaming reader for the Nominatim dump format |
| `geon-index` | The memory mapped index |
| `geon-http` | Minimal Netty HTTP server |
| `geon-photon` | Photon compatible API |
| `geon-app` | The `geon` command line |
| `test/geon-test` | End to end tests |
| `test/geon-test-photon` | Differential tests against a real Photon (`-Pphoton`) |

## License

[0BSD](LICENSE)
