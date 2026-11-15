# Dependency issues

Problems hit while building Geon on its dependencies, with reproductions and the
workarounds in the code. When a fix lands upstream, remove the workaround and its
entry here.

## avaje-json-core 3.16 (no longer used)

Geon read and wrote JSON with avaje-json-core until 2026-11-15, then moved to
[snaphop-jsonl](https://github.com/snaphop/snaphop-jsonl). Three avaje bugs needed
workarounds here, all reported upstream in avaje/avaje-jsonb#607:

- The stream reader could end an array early when a string closed on the last byte
  of its buffer. Dumps were split into lines, and each line parsed on its own.
- A 50,000 character limit on strings and `readRaw()`. Geometries were copied token
  by token.
- `JsonWriter.rawValue(String)` wrote each char as one byte, corrupting non-ASCII
  text. Non-ASCII extra tags were parsed and copied value by value.

With snaphop-jsonl the dump is one stream, and raw JSON is written as it is. The
geometry copy stays because it also computes the bounding box. `LargeJsonTest`
covers all three cases.

## Rainbow Gum 0.17.0 in a GraalVM native image

**Symptom:** with `rainbowgum-jdk` on the classpath, `native-image` fails:

```
An object of type 'io.jstach.rainbowgum.jul.SystemLoggerQueueJULHandler' was found
in the image heap ... reached by
  java.util.logging.LogManager$RootLogger ... LogManager.getLogManager()
```

and, in an earlier attempt, the same for `io.jstach.rainbowgum.SimpleRainbowGum`
through `RainbowGumHolder.rainbowGum`.

**Cause** (from `--trace-object-instantiation`). Netty is not involved; the JDK's
security event logging starts Rainbow Gum while the image is being built:

1. GraalVM's `TrustStoreManagerFeature.afterRegistration` loads the default trust
   store at image build time.
2. `CertificateFactory.generateCertificate` calls `JCAUtil.tryCommitCertEvent`, which
   asks `System.getLogger("jdk.event.security").isLoggable(...)`.
3. That reaches `RainbowGumSystemLoggerFinder.getLogger`. With the default
   `logging.systemlogger.initialize=CHECK`, `InitRouterProvider.router` calls
   `RainbowGum.ofOrNull()` and starts a real Rainbow Gum.
4. Starting it runs `JULConfigurator`, which adds `SystemLoggerQueueJULHandler` to the
   JUL root logger.
5. JUL's `LogManager` is a build time object in native images, so the handler and the
   started gum land in the image heap.

`SystemLoggingFactory`'s javadoc names this exact trigger but assumes only the
"nothing bound yet" state gets frozen. The `CHECK` path breaks that assumption.

**Proposed fix in Rainbow Gum:** while `org.graalvm.nativeimage.imagecode` is
`buildtime` (the property `LogAppender` already checks), the finder should return
`LogRouter.global()` without starting a gum and without marking itself initialized.

**Workaround in Geon** (until a Rainbow Gum release has the fix):

* `geon-app` depends on `rainbowgum-jdk` again, so Geon and Netty (through the JUL
  bridge) log through Rainbow Gum.
* The native build passes `-Dlogging.systemlogger.initialize=false`, so the finder
  never starts a gum while the image is built. The decision is frozen into the image,
  so the finder does not start one at run time either.
* `Main.main` therefore starts Rainbow Gum itself with `RainbowGum.of()` before
  anything logs. On the JVM this is harmless: the finder reuses that gum.

**Fix:** `jstachio/rainbowgum` branch `fix/systemlogger-native-buildtime` makes the
finder return the queuing global router while `org.graalvm.nativeimage.imagecode` is
`buildtime`, without starting a gum or caching that answer. Verified by building
Geon's native image against it with `rainbowgum-jdk` and no SLF4J.

## native-maven-plugin 1.1.18

The plugin's GraalVM reachability metadata repository supplied configs for Netty
4.1.80 and 5.0.0.Alpha2, and excluded Netty 4.2's own `META-INF/native-image`
config. `geon-app/pom.xml` disables the metadata repository; Netty 4.2 ships what it
needs.
