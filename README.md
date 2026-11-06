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
./mvnw install
```

## License

[0BSD](LICENSE)
