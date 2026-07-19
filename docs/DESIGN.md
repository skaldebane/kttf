# kttf Design Direction

This document records the current design direction for **kttf**, a Kotlin-first,
multiplatform font parser inspired by and behaviorally compared against
[`ttf-parser`](https://github.com/harfbuzz/ttf-parser).

It is intentionally detailed, but it is **not a promise that every internal
representation or provisional API will remain unchanged**. Font parsing is a
large, adversarial-input problem, and Kotlin's performance characteristics vary
between backends. Measurements, porting experience, upstream behavior, and real
usage may justify changing decisions documented as provisional.

## Decision language

Each notable choice is classified as one of:

- **Adopted** — the implementation should follow this unless concrete
  evidence shows that it is unsuitable.
- **Provisional** — a deliberate starting point that should be exercised before
  being stabilized.
- **Deferred** — useful work or a possible design that is intentionally outside
  the current scope.
- **Rejected for now** — considered, but currently a worse fit than the adopted
  direction. It may be reconsidered if its motivating constraints change.

Public API remains experimental pending broader production validation. Internal
implementation choices are always subject to profiling and safety review.

## Current scaffold baseline

**Adopted as of 2026-07-18, but expected to move with compatible stable tooling.**

- Project and artifact name: `kttf`.
- Maven group and Kotlin package prefix: `dev.skaldebane` and
  `dev.skaldebane.kttf`.
- Kotlin/Kotlin Gradle Plugin: 2.4.10.
- Gradle Wrapper: 9.5.0 `bin`, with the published distribution SHA-256 pinned in
  `gradle-wrapper.properties`.
- Gradle and Kotlin JVM toolchain: JDK 17, provisioned through Gradle's Foojay
  resolver when it is not installed locally.
- Android Gradle Plugin: 9.3.0, using the dedicated
  `com.android.kotlin.multiplatform.library` plugin rather than legacy
  `androidTarget()` integration.
- Android policy: compile SDK 36 and minimum SDK 21, provisional until actual
  Android consumers exercise the library.
- Dokka: 2.2.0.
- ktlint Gradle plugin: 14.2.0 with ktlint 1.8.0.
- Strict explicit API mode and Kotlin Gradle Plugin ABI validation are enabled.
- JavaScript and Wasm package-manager lock state is retained in the repository.
- Publication metadata uses the verified GitHub identity `skaldebane`, but no
  external release is performed by the scaffold.

The JVM artifact currently targets Java 17. This is a product choice rather than
a parser requirement and can be lowered before the first release if supporting
older JVM consumers is worth the compatibility and testing cost.

The build declares every current nondeprecated target selected in the target
policy below. All declared production target compilations passed on the initial
Linux bootstrap. Runtime tests passed on JVM, Android host JVM, Node.js, Wasm JS,
Wasm WASI, and Linux x64; host-constrained Native runtime tests remain CI work for
matching platforms.

## Project goals

### Primary goals

1. Provide a Kotlin-first API for inspecting and outlining OpenType fonts.
2. Support Kotlin Multiplatform from common Kotlin code.
3. Achieve strong practical performance without making the API hostile to
   idiomatic Kotlin usage.
4. Avoid work and allocation proportional to font or table size unless the
   caller explicitly requests materialization.
5. Treat malformed and adversarial fonts as ordinary input rather than as an
   exceptional implementation condition.
6. Match the observable behavior of a pinned `ttf-parser` revision where that
   behavior is applicable to Kotlin.
7. Make correctness continuously testable through upstream unit-test ports,
   differential testing, public corpora, property tests, and local corpora.

### Non-goals

- A byte-for-byte or name-for-name translation of the Rust public API.
- A general-purpose byte I/O abstraction.
- Production Rust FFI.
- Memory mapping on every platform.
- Proving that every public convenience operation is allocation-free.
- Stabilizing all public APIs before several distinct font-table families have
  exercised the design.
- Reproducing Rust's type system, ownership system, lifetimes, or exact internal
  module boundaries in Kotlin.

## Guiding principles

### Kotlin-first, behaviorally compatible

**Adopted.** Rust APIs and `ttf-parser` are important sources of inspiration,
but kttf should follow Kotlin conventions where they improve clarity and
usability. Compatibility has three distinct meanings:

- **Behavioral compatibility:** observable parsing and query results should
  agree with the pinned upstream revision unless a deviation is documented.
- **Feature compatibility:** supported tables and formats can be tracked against
  upstream, with a visible porting status.
- **API compatibility:** not a goal. Kotlin names, overloads, collection views,
  nullability, exceptions, and callback protocols may differ from Rust.

### Efficient core, expressive layers

**Adopted.** The library should use a small, checked, binary-backed parser core.
Idiomatic conveniences may be layered on top. The existence of an allocating
convenience API must not remove the lower-allocation path.

Examples:

- `names[index]` is the fundamental indexed operation.
- `for (name in names)` is an idiomatic convenience and may allocate one
  iterator.
- A specialized traversal may avoid the iterator where measurements show that
  doing so matters.
- `toList()`, `codePoints()`, decoded strings, and materialized outline data may
  allocate and should document that behavior.

### Measure before specializing

**Adopted.** The first representation should be simple enough to audit. Avoid
introducing backend-specific storage abstractions, manually duplicated parser
implementations, or public inline machinery based only on assumptions.

Specialization should be driven by:

- a representative benchmark;
- an identified allocation or dispatch cost;
- a concrete zero-copy storage use case; or
- a correctness constraint that the simpler representation cannot satisfy.

### No hidden whole-table work

**Adopted.** Scalar queries and construction of binary-backed views should not
silently scan, decode, copy, or materialize an entire font or table. Operations
whose cost is proportional to input size should make that traversal apparent in
name, documentation, or callback shape.

## Source-byte ownership

### Initial input type

**Adopted.** The canonical input is `ByteArray`.

The parser retains the supplied array. It does not copy it during ordinary
construction. This gives all Kotlin targets a simple, contiguous, constant-time
random-access representation with no external dependency.

The public contract must state:

1. kttf retains the supplied `ByteArray`.
2. The caller must not mutate the array while the parsed face or any derived
   view is in use.
3. A face and its views are safe for concurrent reads if the source-retention
   contract is respected and no operation uses shared mutable workspace.
4. Source mutation during use has unspecified behavior. The library does not
   attempt to detect it.

Kotlin has no standard immutable primitive byte-array type. A read-only wrapper
cannot prevent mutation through another reference, and a wrapper interface would
add dispatch to every primitive read unless optimized away. True ownership
isolation requires copying.

### Copying convenience

**Deferred.** A convenience such as `parseCopy` may be added if there is demand.
Callers can initially choose isolation explicitly with `data.copyOf()`.

### Alternate storage

**Rejected for per-byte dispatch.** Do not use a per-byte interface such as:

```kotlin
interface FontData {
    val size: Int
    operator fun get(index: Int): Byte
}
```

It introduces virtual or interface dispatch into essentially every primitive
read and complicates optimization across all backends.

**Adopted for external table containers.** `FontFace.fromTables(Map<Tag,
ByteArray>)` snapshots the map but retains each array directly. This supports
decoders such as WOFF without concatenating tables into a synthetic SFNT and
without pretending kttf owns immutable bytes. The caller must keep every array
unmodified for the lifetime of the face and its views. Since there is no SFNT
directory, table-record enumeration is empty for these faces.

**Deferred.** Other alternate storage may be justified later for JVM memory maps,
Native mapped memory, JS typed arrays, or other zero-copy sources. Possible
future approaches include `expect`/`actual` storage, specialized parser
implementations, or a carefully benchmarked storage abstraction. The public API
should avoid unnecessarily exposing `ByteArray`-specific implementation details
beyond construction so this remains possible.

## Internal binary representation

### Bounded views

**Adopted.** Internal parsing uses bounded, non-copying views over the original
array. The exact class names and field layout are provisional, but the model is:

```kotlin
internal class ByteSlice(
    val data: ByteArray,
    val start: Int,
    val end: Int,
)
```

A slice represents the half-open absolute range `[start, end)` in `data`.
Creating a child slice validates its bounds and does not copy bytes.

### Streams and cursors

**Adopted, representation provisional.** Sequential reads use a local mutable
cursor bounded by a slice. A conceptual representation is:

```kotlin
internal class Stream(
    val slice: ByteSlice,
    var offset: Int = 0,
)
```

The cursor's offset should have one clearly documented coordinate system. The
initial preference is an offset relative to the slice, while the slice retains
absolute array bounds. Primitive reads must fail safely without moving into an
invalid state.

Whether slices and streams are ordinary classes, inline/value wrappers, or
manually flattened fields is benchmark-driven. Auditability takes precedence in
the first implementation.

### Core invariants

**Adopted.** All parser primitives must preserve these rules:

- Slicing does not copy.
- Parser code never uses `ByteArray.copyOfRange` for table parsing.
- Every view has explicit, validated bounds.
- Every `offset + length` is validated without first performing an overflowing
  addition.
- Every `count * elementSize` is validated without first performing an
  overflowing multiplication.
- A decoded 32-bit unsigned file offset is represented in `Long` until it has
  been checked against the bounded input and safely narrowed to `Int`.
- Input-derived counts never directly determine an allocation without an
  independent bound.
- Failed reads return an unambiguous failure value; unchecked array indexing is
  not ordinary parser control flow.
- A failed parser does not publish a partially valid public view.
- Optional malformed tables generally make the corresponding query unavailable;
  they do not corrupt unrelated valid tables.

### Lazy fixed-record arrays

**Adopted in principle; API provisional.** Repeated fixed-size records should be
binary-backed and decoded on access. A conceptual form is:

```kotlin
internal class LazyArray(
    private val bytes: ByteSlice,
    val count: Int,
    private val recordSize: Int,
) {
    inline fun <T> get(index: Int, parse: (ByteSlice, Int) -> T?): T? {
        if (index !in 0 until count) return null
        return parse(bytes, index * recordSize)
    }
}
```

The generic callback may box or allocate depending on backend and call site.
Table-specific accessors or manually inlined loops may replace it after
benchmarking. The important initial property is lazy, bounded, non-copying
record access—not this exact generic type.

### Strings

**Adopted.** Name and metadata strings are decoded only when explicitly
requested. Raw string records remain binary-backed. Decoding methods should
state that they allocate a `String` and may return null when the encoding is
unsupported or malformed.

### Outlines and painting

**Adopted.** Glyph outlines and color painting operations are streamed through
callbacks or sink interfaces instead of being materialized by the parser core.
Materialized path or paint-tree conveniences may be provided separately.

## Numeric representation

### General policy

**Adopted.** Distinguish file encoding from application-domain representation.
A field being unsigned in OpenType does not require Kotlin's corresponding
unsigned type to be used throughout the implementation.

Current rules:

- `Int` for array indices, lengths, counts, Unicode code points, and values that
  have been safely decoded from 8- or 16-bit fields.
- `Long` while decoding and validating unsigned 32-bit values, offsets, and
  intermediate arithmetic that may exceed `Int`.
- Signed primitive storage for fixed-point bit patterns, with explicit
  conversion to floating-point values.
- Semantic value classes for identifiers and bit-pattern values where they
  improve type safety.
- No generic numeric abstraction in parser hot paths.

### Unsigned Kotlin types

**Provisional policy.** Kotlin unsigned primitives are inline classes over
signed primitives and may be cheap in direct code, but they can box in generic,
nullable, collection, interface, and cross-module contexts. Unsigned arithmetic
also wraps, which is undesirable during hostile-input bounds validation.

The parser explicitly decodes unsigned bytes and words into `Int` or
`Long`. Example byte widening uses `byte.toInt() and 0xFF`.

Unsigned types may still be appropriate in selected public APIs where
unsignedness is genuinely part of the domain. This should be decided per API and
verified across targets rather than adopted globally.

`UByteArray` does not provide immutable storage and is not an ownership solution.

### Semantic types

**Adopted, exact set provisional.** Candidate value classes include:

- `Tag`
- `GlyphId`
- `NameId`
- fixed-point wrappers where retaining exact bits matters

A semantic wrapper is preferred over exposing a raw `UShort` when users should
not perform arbitrary unsigned arithmetic on the value. Value-class boxing must
still be measured in nullable, generic, interface, and collection contexts.

### Font-size limit

**Adopted.** A `ByteArray` is `Int`-sized. kttf therefore supports inputs whose
storage and validated ranges fit Kotlin array indexing. This is adequate for
real-world fonts and keeps the common implementation straightforward.

## Public API posture

### Experimental period

**Adopted.** The binary substrate and all planned table families have exercised
the design. Public API remains experimental until broader corpus, differential,
fuzz, performance, and consumer validation justify stabilization.

This does not mean design discipline is optional. It means early releases may
make source- and binary-incompatible improvements before stabilization.

### Parsing entry points

**Adopted.** Provide throwing and nullable entry points:

```kotlin
public class FontFace private constructor(/* retained state */) {
    public companion object {
        public fun parse(data: ByteArray, faceIndex: Int = 0): FontFace
        public fun parseOrNull(data: ByteArray, faceIndex: Int = 0): FontFace?
    }
}
```

- `parse` throws a documented parse exception for invalid top-level input or an
  invalid face index.
- `parseOrNull` returns null for those failures.
- Internal parsing does not use exceptions for expected malformed-data control
  flow.

A top-level factory may prove more idiomatic once real call sites exist. Naming
and placement remain provisional.

### Kotlin `Result`

**Rejected for the initial API.** `Result<T>` is a value class, but that does not
make every use allocation-free across every backend. Its representation and
boxing behavior vary by contained type and context, and failure naturally wraps
a `Throwable`.

More importantly, malformed input is an expected validation outcome. `parse`
and `parseOrNull` cover the initial common cases without freezing a diagnostic
model.

### Diagnostics

**Adopted.** Throwing parse entry points expose a `FontParsingError` on
`FontParsingException`, distinguishing malformed containers, unknown magic,
face-index bounds, and each required table. Nullable entry points intentionally
discard the diagnostic.

### Scalar queries

**Adopted.** Common scalar operations should be direct and allocation-free after
face construction where practical:

```kotlin
val glyph = face.glyphId('A'.code)
val units = face.unitsPerEm
val advance = glyph?.let(face::glyphHorizontalAdvance)
```

Exact nullability and naming will follow upstream behavior and Kotlin call-site
experiments.

### Indexed views

**Adopted.** Repeated records exposed publicly should usually use immutable,
binary-backed views with indexed access and idiomatic iteration:

```kotlin
public class Names internal constructor(/* binary view */) : Iterable<Name> {
    public val size: Int
    public operator fun get(index: Int): Name
    override fun iterator(): Iterator<Name>
}
```

Users should be able to write:

```kotlin
for (name in face.names) {
    // ...
}
```

The iterator is created on demand and may allocate once per loop. Depending on
the record representation, elements may also allocate or box. This is accepted
as an idiomatic convenience, not assumed to be the fastest path.

Do not automatically implement `List<T>`. `List` carries broader behavioral and
performance expectations and exposes many operations that encourage
materialization. Specific views can adopt richer collection interfaces later if
real use shows that the contract fits.

### Traversal APIs

**Adopted.** Single-operation traversal can use a lambda:

```kotlin
face.forEachCodePoint { codePoint ->
    // ...
}
```

Multi-operation protocols use interfaces:

```kotlin
public interface OutlineSink {
    public fun moveTo(x: Float, y: Float)
    public fun lineTo(x: Float, y: Float)
    public fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float)
    public fun curveTo(
        control1X: Float,
        control1Y: Float,
        control2X: Float,
        control2Y: Float,
        x: Float,
        y: Float,
    )
    public fun close()
}
```

The shared outline protocol uses `Float` coordinates and calculated
`FontBounds`; its naming remains experimental with the rest of the public API.

### Public inline functions

**Provisional and conservative.** Public inline traversal may remove iterator or
lambda overhead, but it also embeds implementation into consumer binaries,
requires visibility accommodations for internal state, increases published ABI,
and makes implementation evolution harder.

Do not inline public APIs reflexively. Begin with clear APIs and benchmark real
call sites. Add or inline specialized traversal only when it demonstrates a
meaningful benefit across relevant targets.

### Materializing conveniences

**Adopted as a policy; deferred individually.** Ergonomic APIs that allocate are
allowed. KDoc should identify materialization when it may be surprising or
proportional to the font/table size. Conventional names such as `toList`,
`toSet`, `decodeToString`, and `copy...` already communicate allocation and
should be preferred where accurate.

Potential conveniences include:

- collecting code points into `IntArray`;
- collecting names into a list;
- decoding all name strings;
- materializing outline commands or platform path objects.

Platform-specific path adapters should not leak into the common parser core.

## Allocation and performance model

### Working definition

**Adopted.** “Zero allocation” is not a blanket product claim. A more useful
initial target is:

> After receiving source bytes, parsing a face and querying scalar or
> binary-backed table data performs no allocations proportional to font or
> table size.

Separately, aim for zero transient allocation in hot operations where practical,
including glyph lookup, metrics lookup, bounds calculation, and outline
streaming.

### Avoid in hot internals

- `Sequence`
- iterator-based internals where a direct index loop is clearer
- stored lambda objects
- `Pair`, `Triple`, and generic `Result` in hot paths
- materialized data-class records in tight loops without measurement
- `ByteArray.copyOfRange`
- generic numeric abstractions
- unsigned arrays used only because the file encoding is unsigned
- one persistent object per table-directory record unless justified
- collections for glyph points, CFF operands, and similar transient stacks
- recursion without a strict, format-aware bound

### Prefer in hot internals

- explicit `while` loops
- primitive fields
- fixed-size or bounded primitive scratch arrays where required
- binary-backed views
- sentinel/null failure values where unambiguous
- bounded iterative interpreters
- callback visitors and sink interfaces
- index-based public access
- reusable operation workspaces

### Scratch storage

**Provisional.** Some complete operations, especially variable-font processing,
require scratch storage. Kotlin does not expose portable stack arrays. Candidate
strategies are:

1. reusable workspace owned by an operation context;
2. caller-provided workspace for advanced users;
3. a bounded allocation per operation;
4. a common non-allocating fast path plus a complete allocating path.

The preferred direction is reusable or caller-provided workspace internally,
with a convenient allocating public overload if needed. Reentrancy and
concurrent access must be explicit: a shared mutable workspace cannot silently
make an otherwise immutable face unsafe.

## Safety and malformed input

### General guarantee

**Adopted.** Malformed fonts must not cause unchecked indexing, integer overflow,
unbounded allocation, unbounded recursion, hangs, or publication of invalid
views.

### Required controls

- checked slice endpoints;
- checked offset addition and record-size multiplication;
- validation before narrowing `Long` to `Int`;
- explicit recursion or graph-depth limits for composite glyphs, CFF
  subroutines, COLR graphs, and similar structures;
- shared execution budgets for recursive interpreters where a depth limit alone
  still permits exponential work;
- allocation caps independent of untrusted counts;
- bounded loop progress so malformed offsets cannot create infinite loops;
- careful handling of zero-length and terminal ranges;
- no assumption that table records are sorted unless validated or the algorithm
  is safe when they are not;
- no assumption that optional tables are internally consistent with mandatory
  tables;
- propagation of callback exceptions without corrupting persistent parser state;
- a defined position on reentrant API calls from callbacks.

### Query behavior

**Provisional.** Invalid top-level containers fail construction. Malformed
optional tables should generally cause their corresponding queries to return
null, an empty view, or a documented failure rather than throwing from ordinary
access. Exact behavior should track upstream semantics where useful and remain
consistent within each API family.

### Source mutation

Mutation of retained source bytes violates the public contract. Tests should not
accidentally promise deterministic behavior after mutation.

## Kotlin Multiplatform strategy

### Common implementation

**Adopted.** Parser implementation and ordinary unit tests live in common source
sets. Platform code should be limited to adapters, integration tests, benchmark
launchers, publication configuration, and functionality that genuinely cannot be
expressed in common Kotlin.

### Target breadth

**Adopted as intent; exact declarations follow current tool support.** Publish for
all actively supported Kotlin targets that are practical for a general-purpose
library. The target list should be derived from the current Kotlin release and
host constraints rather than frozen in this document.

Expected families include:

- JVM;
- Android;
- JavaScript;
- WebAssembly targets supported by the chosen Kotlin release;
- Linux architectures supported by the chosen Kotlin release;
- Windows/MinGW;
- macOS;
- iOS;
- tvOS;
- watchOS;
- Android Native where current tooling makes publication support reasonable.

Deprecated targets should not be resurrected merely for numerical completeness.
Experimental targets and targets requiring special hosts should be clearly
identified in the build and CI documentation.

### Host constraints

**Adopted.** A Linux checkout should remain usable even when Apple artifacts
cannot be compiled locally. Build configuration may declare Apple targets while
CI uses suitable macOS hosts for compilation and tests. If the current Kotlin
plugin requires host gating for particular targets or tasks, use the least
surprising documented pattern.

### Test execution

**Adopted.** Shared test source does not mean one backend proves all backends.
Generated code and runtime behavior differ in:

- value-class and lambda boxing;
- iterator allocation;
- integer and floating-point corner cases;
- recursion and stack size;
- exception behavior;
- concurrency and memory management;
- string decoding;
- compiler backend bugs.

A practical initial split is:

- full unit and broad fixture suite on JVM;
- full common unit suite on Linux x64 Native;
- common unit suite on JavaScript and WebAssembly;
- representative tests on Windows and Apple hosts in CI;
- compile checks for additional published targets;
- periodic corpus and benchmark jobs rather than running every large corpus on
  every target for every change.

This is a starting policy, not a permanent CI matrix.

## Testing strategy

### 1. Ported upstream unit tests

**Adopted.** Port all relevant parser and table tests alongside the corresponding
implementation. Preserve malformed-input cases and expected behavior rather than
only porting happy paths.

Where Rust's type system prevents a state that Kotlin can represent, add a
Kotlin-specific test or strengthen the internal constructor so the invalid state
cannot be created.

Maintain traceability from Kotlin tests to the pinned upstream source path and
revision without turning every test into a large attribution comment.

### 2. Kotlin-specific safety tests

**Adopted.** Add tests for guarantees Rust's compiler or standard idioms provide
but Kotlin does not automatically enforce:

- every prefix/truncation boundary of small valid fixtures;
- `Int` and `Long` arithmetic boundaries;
- offset addition and count multiplication overflow;
- source retention and absence of implicit copies where observable;
- invalid child slices;
- cursor state after failed reads;
- callback exception propagation;
- reentrant callback behavior once defined;
- concurrent reads;
- recursion/depth limits;
- hostile counts that must not trigger proportional allocation;
- consistent results across representative backends;
- nullable/value-class/generic boxing-sensitive paths where performance matters.

### 3. Checked-in fixtures

**Adopted.** Keep small, redistributable fixtures needed by unit tests. Preserve
upstream fixture licenses and attribution. Prefer synthetic byte sequences for
narrow parser behavior and tiny real fonts for integration behavior.

### 4. Differential oracle

**Adopted.** Pin an exact `ttf-parser` commit. Build a small Rust executable that
emits canonical observable results for a font and face index. Initial output may
cover:

- collection/face count;
- table presence and lengths;
- face metrics and global properties;
- cmap mappings and traversed code points;
- names and decoded strings;
- horizontal and vertical metrics;
- variation axes and coordinates;
- glyph bounds;
- normalized outline command streams;
- color-paint operations.

The format should describe observable values rather than serialize upstream
internal structures. Floating-point comparison rules must be explicit.

### 5. Checked-in oracle output

**Adopted.** Generate canonical oracle output for committed fixtures and check it
in as test resources. Every Kotlin backend can then test against the same
expected behavior without installing or embedding Rust.

The canonical format should begin readable and easy to debug. A compact binary
format can replace or supplement it only if corpus size becomes a demonstrated
problem.

### 6. Live host-side differential tests

**Adopted and implemented for checked and generated fixtures.** Host-side Gradle
tasks run both implementations and report canonical observable differences.
Arbitrary-directory orchestration remains part of broader corpus validation.

The integration uses a standalone Rust CLI rather than FFI. The toolchain remains
test-only and does not become a production dependency.

### 7. Gobley/Rust FFI

**Deferred.** Gobley may become valuable for in-process differential fuzzing or
when process orchestration is a measured bottleneck. It should not be an initial
architectural dependency because it expands cross-compilation, packaging,
Android/AGP, JS/Wasm, and CI complexity without helping the production library.

### 8. Public font corpus

**Adopted as a later testing layer.** Build a pinned, licensed corpus from public
sources such as Google Fonts/Noto, Source families, STIX Two, Font Awesome,
public color-font fixtures, Adobe variable-font fixtures, and relevant HarfBuzz
test fonts.

Every acquired file should record:

- source URL;
- source revision or release;
- file path;
- license and redistribution status;
- SHA-256;
- reason for inclusion or feature coverage.

Large corpora need not be committed directly if a reproducible acquisition task
and license permit downloading them in CI.

### 9. Machine-local corpus

**Adopted as a later testing layer.** Provide a task accepting one or more local
directories, recursively discovering `ttf`, `otf`, `ttc`, and `otc` inputs. This
allows contributors to test system and proprietary fonts without uploading or
committing them.

Reports should default to paths, hashes, and mismatches. They must not upload font
contents.

### 10. Property and mutation testing

**Adopted.** Important invariants include no crash, no hang, no stack exhaustion,
and no unbounded allocation under truncation, offset corruption, oversized
counts, arithmetic boundaries, recursion cycles, and random mutations.

A focused corpus reducer or table-stripper similar to upstream's fuzz tooling is
useful later. Ordinary unit tests should first make the binary substrate robust.

## Performance infrastructure

### Benchmarks

**Adopted.** Add common benchmark definitions using the current supported Kotlin
benchmark tooling. Interpret results per backend; raw results from different
machines or runtimes are not directly comparable.

Representative operations should eventually include:

- face construction;
- collection face selection;
- table lookup;
- cmap glyph lookup;
- metrics lookup;
- name iteration without decoding;
- name decoding;
- code-point traversal;
- simple and composite TrueType outlining;
- CFF/CFF2 outlining;
- variable outline processing;
- color-paint traversal.

Representative fixtures should include tiny synthetic data, an ordinary Latin
font, a large CJK font, CFF/CFF2, variable, and color fonts.

### Allocation measurement

**Adopted.** Throughput alone is insufficient. Track JVM allocation for hot
operations and use available Native/JS/Wasm memory tooling where meaningful.
Avoid making strict allocation counts a cross-target gate until the measurement
method is reliable.

### Performance gates

**Deferred.** Establish baseline history early, but do not enforce brittle
performance thresholds while APIs and compiler versions are moving. Add gates
for large regressions once benchmarks are stable and reproducible.

## Build and module structure

### Initial modules

**Adopted.** Start with one publishable Kotlin Multiplatform library module. Use
packages/directories to isolate parser substrate, tables, outlines, variation,
and color support.

Do not create one Gradle module per table. Extra module boundaries increase build,
publication, API, and dependency complexity without improving parser runtime.

Likely initial source layout:

```text
src/commonMain/kotlin/
  .../kttf/
  .../kttf/internal/
  .../kttf/internal/parser/
  .../kttf/internal/tables/
src/commonTest/kotlin/
  .../kttf/
  .../kttf/internal/parser/
  .../kttf/internal/tables/
src/commonTest/resources/
```

The exact package prefix follows project publishing identity and may be adjusted
before the first release.

### Optional modules

**Deferred.** Separate adapters, corpus tools, benchmarks, or platform path
integrations when they have distinct dependencies or publication needs. Module
splitting is not a parser optimization by itself.

### API validation and documentation

**Adopted in principle.** Use explicit API mode and current Kotlin-library
practices for API dumps/compatibility validation, documentation, formatting, and
static analysis when compatible with the selected Kotlin/Gradle versions.

Avoid adding overlapping quality plugins merely to make the scaffold look
complete. Every plugin should have a clear role and work across the target
matrix.

## Licensing and upstream attribution

### Project license

**Adopted.** kttf uses the Apache License, Version 2.0.

Reasons:

- `ttf-parser` permits Apache-2.0 use;
- Apache-2.0 is familiar in the Kotlin, JetBrains, and Android ecosystems;
- it includes an explicit patent grant and patent-termination terms;
- it is suitable for broad commercial and open-source adoption.

### Required project files

**Adopted.** Maintain:

- `LICENSE` with the Apache-2.0 text;
- `NOTICE` identifying derived portions and upstream contributors without
  implying endorsement;
- README attribution and an upstream link;
- `UPSTREAM.md` containing the pinned commit, version, port status, known
  deviations, test mapping, licenses, and update procedure;
- preserved copyright and license notices where required;
- file-level notices where a file is a substantial direct translation and a
  notice materially improves compliance/traceability.

Attribution in README alone is insufficient.

### Porting style

**Adopted.** Preserve algorithms and observable semantics where intended, but
write Kotlin that fits surrounding Kotlin code. Record substantial deviations
and avoid claiming official affiliation with upstream.

## Initial milestone

The first milestone should validate the safety model and API direction before
porting hundreds of table parsers.

### Phase A: project foundation

1. Create the Kotlin Multiplatform build using current stable tooling.
2. Configure explicit API mode and a broad, documented target matrix.
3. Add Apache-2.0 licensing and upstream attribution scaffolding.
4. Pin an exact upstream revision in `UPSTREAM.md`.
5. Add common tests and representative local test tasks.
6. Add formatting/static analysis/API tooling only where it has a clear,
   compatible role.

### Phase B: checked parser substrate

1. `ByteSlice` or equivalent bounded view.
2. `Stream`/cursor with checked primitive reads.
3. big-endian integer readers.
4. checked slicing, offset resolution, and count multiplication.
5. `Tag` and initial semantic identifiers.
6. fixed-record lazy access where immediately useful.
7. direct ports of relevant upstream parser tests.
8. Kotlin-specific truncation and arithmetic tests.

### Phase C: container and table directory

1. detect raw SFNT and supported collection containers;
2. validate face index;
3. parse table directory records;
4. provide non-copying raw table lookup internally;
5. avoid creating one long-lived object per table record unless benchmarks and
   API design justify it;
6. port upstream container/directory tests.

### Phase D: minimal `FontFace`

Implement enough tables for an end-to-end useful face:

- `head`;
- `maxp`;
- `hhea`;
- `hmtx`;
- `cmap`.

Expose provisional scalar metrics, glyph lookup, and code-point traversal. This
set exercises offsets, fixed records, table lookup, scalar properties, and
streaming traversal.

### Phase E: first differential oracle

Build the Rust CLI and canonical output once the Kotlin implementation can parse
and query the minimal face. Compare behavior before broad table expansion so
mistaken substrate assumptions are caught early.

## Decisions intentionally left open

The following remain intentionally open:

- whether selected parser helpers should be inline;
- whether some views should implement richer collection interfaces;
- exact public use of unsigned types;
- workspace ownership and reentrancy model;
- alternate zero-copy storage;
- memory mapping;
- Gobley/in-process Rust testing;
- module split beyond the core library;
- performance regression thresholds;
- full corpus acquisition/storage mechanism;
- stabilization schedule;
- exact published target list when Kotlin tooling changes;
- Java-specific overloads and annotations beyond normal Kotlin/JVM interop.

## Review triggers

Revisit this document when any of these occurs:

- representative parser and outline paths are benchmarked;
- variable-font or color processing requires a different scratch-storage model;
- color painting requires a richer callback protocol;
- a non-`ByteArray` zero-copy use case is proposed;
- a target exposes materially different performance or correctness behavior;
- the first public release is prepared;
- the pinned upstream revision is updated.

When a decision changes, update this document with the new rationale rather than
preserving an obsolete design merely for consistency with the first draft.
