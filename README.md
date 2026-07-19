# kttf

kttf is an experimental Kotlin Multiplatform OpenType font parser. It provides
a Kotlin-first API while tracking the behavior and feature set of a pinned
[`ttf-parser`](https://github.com/harfbuzz/ttf-parser) revision.

## Features

- SFNT and TrueType Collection parsing with bounded, zero-copy table views.
- Unicode cmap lookup, names, metrics, metadata, and glyph names.
- TrueType, CFF1, CID CFF1, and variation-aware CFF2 outlines.
- Variable fonts through `fvar`, `avar`, `gvar`, HVAR, VVAR, and MVAR.
- CPAL/COLR color glyphs, SVG documents, and embedded bitmap strikes.
- OpenType GDEF, GPOS, GSUB, and MATH query views.
- Apple `kern`, `kerx`, `morx`, `ankr`, `feat`, and `trak` query views.
- JVM, Android, JS, Wasm, Linux, Windows, and Apple Kotlin targets.

Layout APIs expose parsed font data; kttf is not a text-shaping engine.

## Usage

```kotlin
fun inspectFont(bytes: ByteArray) {
    val face = FontFace.parse(bytes)
    val glyph = face.glyphId('A') ?: return

    println(face.names.firstOrNull()?.decodeToString())
    println(face.glyphHorizontalAdvance(glyph))
    println(face.glyphBoundingBox(glyph))

    val heavier = face.withVariations(mapOf(Tag.parse("wght") to 700f))
    println(heavier.glyphBoundingBox(glyph))
}
```

`FontFace.fromTables` can parse independently stored table arrays, such as
tables produced by an external WOFF decoder.

## Byte Ownership

kttf retains input `ByteArray` instances without copying them. Do not mutate
source or table arrays while a face or derived view is in use. Pass
`bytes.copyOf()` when snapshot isolation is required.

## Build

The project uses the Gradle Wrapper and JDK 17.

```shell
./gradlew jvmTest jsNodeTest wasmJsNodeTest wasmWasiNodeTest
./gradlew linuxX64Test testAndroidHostTest
./gradlew ktlintCheck checkKotlinAbi verifyCmapDifferential
```

Some Native and browser tasks require matching host toolchains.

## Status

The parser implements the table and face behavior of the pinned upstream
revision, apart from documented intentional deviations and gaps shared with
upstream. The remaining work is production validation: broader licensed-font
corpora, fuzzing, differential coverage, and performance measurement.

The API is still maturing. See [UPSTREAM.md](UPSTREAM.md) for the exact pin
and compatibility status, and [docs/DESIGN.md](docs/DESIGN.md) for design and
safety decisions.

## License

kttf is licensed under the [Apache License, Version 2.0](LICENSE). Upstream
attribution is recorded in [NOTICE](NOTICE).
