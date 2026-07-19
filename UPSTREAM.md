# Upstream tracking

kttf is an independent Kotlin port inspired by and behaviorally compared with
[`ttf-parser`](https://github.com/harfbuzz/ttf-parser).

## Pinned revision

- Repository: <https://github.com/harfbuzz/ttf-parser>
- Crate version reported by the pinned source: `0.25.1`
- Commit: `6e75b3c539ef6bfbacb020aaa3226414ddea7ce5`
- Commit subject: `chore: Deduplicate vhea parsing (#204)`
- Local reference checkout: `~/Code/temp/ttf-parser`
- Pin date: 2026-07-18

The pinned commit is newer than the `v0.25.1` release tag despite retaining the
same crate version. Compatibility claims must therefore name the commit, not
only the semantic version.

## License selection

Upstream is available under `MIT OR Apache-2.0`. kttf uses upstream-derived
material under the Apache License, Version 2.0. The root `LICENSE` contains the
Apache-2.0 license text, and `NOTICE` records attribution.

Some upstream font fixtures have licenses separate from the source repository.
A fixture is not copied into kttf until its provenance and redistribution terms
have been checked. Fixture-specific license files and attribution must be copied
alongside any redistributed fixture.

Known upstream fixture notes at the time of pinning:

- `tests/fonts/colr_1.ttf` and `colr_1_variable.ttf` originate from Google's
  color-fonts project and have a bundled Apache-2.0 license.
- Source Sans benchmark fonts are licensed under the SIL Open Font License 1.1.
- `tests/fonts/bitmap.otb` is documented upstream as using the source project's
  license.
- Provenance for other fixtures must be verified before redistribution rather
  than inferred from their presence in the repository.

## Compatibility policy

kttf distinguishes:

- **Behavioral compatibility:** observable parsing and query behavior agrees
  with this pinned revision unless a deviation is documented.
- **Feature compatibility:** implemented table and format coverage can be
  tracked against this revision.
- **API compatibility:** not a goal. kttf exposes a Kotlin-first API and does not
  mechanically reproduce Rust signatures or ownership patterns.

Upstream internals are implementation references, not a public-API mandate.
Canonical differential output should describe observable values rather than
serialize Rust-private structures.

## Original port boundary

The original implementation slice covered:

1. checked bounded byte views and streams;
2. big-endian primitive decoding and checked offset arithmetic;
3. tags and table records;
4. raw SFNT/TTC face selection and table lookup;
5. parser-required `head`, `hhea`, and `maxp` parsing;
6. `hmtx` as the first format-required but parser-optional dependent table;
7. relevant synthetic tests from upstream's parser/table test suite.

Subsequent milestones completed cmap, outlines, CFF/CFF2, variations, color,
bitmap, AAT, and OpenType layout. The table below is authoritative for current
status.

## Port status

Status values are `not started`, `partial`, `ported`, and `verified`.
`verified` means relevant Kotlin tests are ported and differential behavior has
been checked where an oracle exists.

| Area | Upstream reference | Status | Notes |
|---|---|---:|---|
| Primitive parser | `src/parser.rs` | ported | Bounded slices, streams, fixed records, variable offsets, and the primitive and fixed-point decoding required by all ported tables are implemented. |
| SFNT/TTC directory | `src/lib.rs` | ported | Includes face selection, complete TTC offset-array validation, exact public record enumeration, sequential known-table collection, binary-searched raw lookup, and retained independent table arrays. |
| `head` | `src/tables/head.rs` | ported | Initial parser-required table; exposes units per em and bounds. |
| `hhea` | `src/tables/hhea.rs` | ported | Exposes explicitly raw `hhea` metrics rather than high-level OS/2 fallback semantics. |
| `maxp` | `src/tables/maxp.rs` | ported | All five relevant upstream synthetic tests are ported. |
| `hmtx` | `src/tables/hmtx.rs` | ported | All eight relevant upstream synthetic tests are ported, including omitted trailing bearings. |
| `cmap` | `src/tables/cmap` | verified | Formats 0, 2, 4, 6, 10, 12, 13, and 14 are ported; format 8 is recognized but unsupported like upstream. Includes high-level Unicode and variation lookup, low-level views, upstream behavior tests, and a checked Rust oracle fixture. |
| `loca` / `glyf` | `src/tables/loca.rs`, `glyf.rs` | verified | Static simple and composite TrueType outlines, transforms, bounded recursion, calculated bounds, malformed-input tests, and live Rust command-stream comparison are implemented. |
| `name` | `src/tables/name.rs`, `src/language.rs` | verified | Binary-backed format 0/1 records, strict deferred Unicode decoding, iterable public views, Windows LANGID and classic Macintosh English resolution, and differential metadata output. |
| `OS/2` | `src/tables/os2.rs` | verified | Versions 0-5, classification, permissions, ranges, varied script/strikeout metrics, high-level fallback semantics, and differential output. |
| `post` | `src/tables/post.rs` | ported | Versions 1/2/2.5/3/4 metadata and version-2 glyph-name lookup are integrated; broader differential fixtures remain. |
| `vhea` / `vmtx` | `src/tables/vhea.rs`, `hmtx.rs` | ported | Vertical headers, advances, and top side bearings are integrated and unit-tested. |
| CFF/CFF2 | `src/tables/cff` | ported | CFF1/CFF2 INDEX/DICT parsing, CID font dictionaries, Type 2 outlines, variation blends, charsets, encodings, glyph names, direct widths, matrices, CID mappings, and `seac` are integrated. Broader real-font differential coverage remains. |
| Variations | variable table modules | ported | `fvar`, `avar`, `gvar`, HVAR, VVAR, and MVAR are parsed and integrated through immutable derived faces, varied metrics, TrueType outlines, and CFF2. |
| Color fonts | color table modules | ported | CPAL, COLR v0/v1 command painting with embedded variation-store and var-index-map deltas, SVG documents, sbix, and CBLC/CBDT plus legacy bitmap aliases are integrated behind public value and sink APIs. |
| Layout | `src/ggg`, `src/aat` | ported | GDEF/GPOS/GSUB/MATH and ankr/feat/trak/kern/kerx/morx parsing is integrated behind Kotlin views and direct observable queries. Public AAT views include bounded state classes/entries/actions/anchors, kerx tuple counts, morx chain defaults/features, and every parsed morx subtable payload; this is not a shaping engine. |
| Differential CLI | `tools/ttf-parser-oracle` | partial | Pinned standalone Cargo CLI and checked basic cmap fixture are implemented. Broader real-font and corpus orchestration remains later work. |

## Known and intentional deviations

- Kotlin cannot express a borrow of immutable bytes. kttf retains a mutable
  `ByteArray` and requires callers not to mutate it while parsed views exist.
- Unsigned file fields are initially decoded into checked `Int`/`Long` values
  rather than propagated as Kotlin unsigned types.
- Rust panics, integer-wrap behavior, and allocation failures are not treated as
  compatibility requirements. In particular, the upstream extreme TTC-count
  panic case becomes a deterministic parse failure.
- Raw `hhea` values remain explicitly named `hheaAscender`, `hheaDescender`, and
  `hheaLineGap` alongside high-level OS/2 and variation-aware properties.
- Public collection views use idiomatic Kotlin iteration where it preserves
  bounded, lazy access, even when upstream exposes indexed or callback-only
  traversal.
- Public API names and error shapes may differ to fit Kotlin conventions.
- `fromTables` retains independently supplied arrays and has no synthetic SFNT
  directory; consequently its `tableRecords` view is empty while `table` and
  `parsedTables` expose the supplied and successfully parsed tables.
- Low-level cmap APIs represent raw unsigned 32-bit code-point values as `Long`;
  high-level face lookup accepts and validates Kotlin `Int` Unicode scalars.
- Low-level cmap traversal returns `false` after 1,114,112 callbacks. A valid
  Unicode cmap cannot define more distinct scalar positions, while this bound
  prevents tiny malformed format 12/13 tables from requesting billions of
  callbacks. Upstream performs the full malformed range traversal.
- Composite glyph point-attachment arguments are consumed safely but point
  matching is not implemented, matching upstream's documented feature gap while
  avoiding upstream's malformed-stream desynchronization behavior.
- Format-1 name tables follow the OpenType-specified record order rather than
  the pinned parser's nonstandard language-tag-first interpretation.
- Unicode name decoding rejects odd byte lengths instead of silently dropping
  a trailing byte.
- Each CFF Type 2 operation is limited to 100,000 decoded operators across the
  glyph and all nested subroutine calls. Upstream bounds recursion depth but can
  perform exponential work through branching subroutine calls.

Additional deviations discovered during porting must be added here with the
reason and corresponding tests.

## Original upstream test mapping

The original implementation ported the relevant fixture-free cases from:

- `tests/tables/main.rs` — eight directory and collection cases;
- `tests/tables/maxp.rs` — five cases;
- `tests/tables/hmtx.rs` — eight cases.
- `tests/tables/cmap.rs` — all sixteen cases, with equivalent shared builders
  used where several upstream tests differ only in encoded segment values.

The suite now also covers every ported table family, malformed boundaries,
source retention, public views, variation behavior, callback propagation, and
execution on JVM, Android host, JS, Wasm JS, Wasm WASI, and Linux x64. The
upstream panic-oriented extreme collection-count case deliberately asserts a
safe Kotlin parse failure instead.

## Update procedure

1. Fetch upstream and select an exact commit.
2. Review upstream license and fixture-license changes.
3. Compare parser substrate, public behavior, table modules, tests, fuzz targets,
   and changelog since the previous pin.
4. Update the pinned commit and metadata in this file in the same change as the
   port.
5. Port changed relevant tests before or alongside implementation changes.
6. Regenerate checked-in differential oracle output.
7. Run unit, differential, malformed-input, corpus, and benchmark checks that
   exist at that stage of the project.
8. Record behavioral deviations rather than silently preserving old behavior.
9. Update the port-status table.

Do not update the semantic version alone: upstream may contain unreleased commits
without changing `Cargo.toml`, as this initial pin demonstrates.
