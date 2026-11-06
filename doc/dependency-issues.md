# Dependency issues

Problems hit while building Geon on its dependencies, with reproductions and the
workarounds in the code. When a fix lands upstream, remove the workaround and its
entry here.

## avaje-json-core 3.16

### `JsonWriter.rawValue(String)` corrupts non-ASCII text

**Symptom:** text written with `rawValue` loses every character above U+007F. Each
one comes out as the replacement character `EF BF BD` (or as stray null bytes once
the response is decoded).

**Reproduction:**

```java
var w = JsonStream.builder().build().bufferedWriterAsBytes();
w.beginObject();
w.name("ok");
w.value("安");                   // correct UTF-8: E5 AE 89
w.name("raw");
w.rawValue("{\"x\":\"安\"}");     // written as EF BF BD
w.endObject();
```

The same happens with `bufferedWriter()`. `value(String)` is correct; only
`rawValue` is affected. It looks like each `char` is written as a single byte.

**Impact on Geon:** stored `extra` tags (for example `official_name:zh`) and the
echoed values in validation errors were corrupted.

**Workaround:** `geon-photon/.../RawJson.java` uses `rawValue` only for pure ASCII and
otherwise reparses the JSON and copies it value by value.

### `readRaw()` is limited to 50,000 bytes when reading a stream

**Symptom:** reading a large value with `readRaw()` from an `InputStream` fails:

```
io.avaje.json.JsonDataException: Maximum buffer limit exceeded for raw content:
'50000' at position: 52767
```

Reading the same 460 KB value from a `byte[]` works. The limit is
`jsonb.parserMaxStringBuffer` (env `JSONB_PARSER_MAX_STRING_BUFFER`), a global setting.

**Impact on Geon:** GeoJSON geometries in planet dumps (country and coastline
boundaries) are many megabytes, and dumps are always streamed.

**Workaround:** `geon-dump/.../Json.java` never uses `readRaw`. It copies geometries
token by token into a writer, which also lets it compute the bounding box in the
same pass.

**Question for upstream:** is the limit meant to apply to `readRaw`? A per reader
setting would be friendlier than a global property.

### Pretty printing drops `null` members

With `w.pretty(true)` and the default `serializeNulls(false)`, `nullValue()` after a
`name(...)` writes nothing. That is the documented default, but it surprised us in a
generic JSON copy. Not a bug; noted because a copy utility needs
`serializeNulls(true)`.

## Rainbow Gum 0.17.0 in a GraalVM native image

**Symptom:** with `rainbowgum-jdk` (which brings `rainbowgum-jul`), `native-image`
fails:

```
An object of type 'io.jstach.rainbowgum.jul.SystemLoggerQueueJULHandler' was found
in the image heap ... reached by
  java.util.logging.LogManager$RootLogger ... LogManager.getLogManager()
```

GraalVM initializes `java.util.logging.LogManager` at build time. By then the JUL
bridge has installed its handler on the root logger, so the handler lands in the
image heap. The first attempt reported the same for `SimpleRainbowGum` through
`RainbowGumHolder`, which `rainbowgum-jdk` marks `--initialize-at-build-time`.

**Workaround:** `geon-app` depends on `rainbowgum-systemlogger` only. Geon logs
through `System.Logger`. Netty logs through JUL, which now goes to JUL's default
console handler instead of Rainbow Gum.

**To do:** find which build time path triggers the JUL install (likely Netty's
logger factory probing JUL during class initialization) and fix it in Rainbow Gum,
or bridge Netty's `InternalLoggerFactory` to `System.Logger`.

## native-maven-plugin 1.1.18

The plugin's GraalVM reachability metadata repository supplied configs for Netty
4.1.80 and 5.0.0.Alpha2, and excluded Netty 4.2's own `META-INF/native-image`
config. `geon-app/pom.xml` disables the metadata repository; Netty 4.2 ships what it
needs.
