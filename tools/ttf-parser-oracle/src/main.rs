use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::panic::{self, AssertUnwindSafe};
use std::path::Path;

use ttf_parser::{cmap, Face, GlyphId, OutlineBuilder, Tag};

const UPSTREAM_COMMIT: &str = "6e75b3c539ef6bfbacb020aaa3226414ddea7ce5";
const MAX_CODE_POINT_TRAVERSAL: usize = 0x110000;

#[derive(Debug)]
struct TraversalLimit;

fn main() {
    let mut args = env::args().skip(1);
    let input = args.next().unwrap_or_else(|| usage());
    let mut face_index = 0;
    let mut expected = None;

    while let Some(argument) = args.next() {
        match argument.as_str() {
            "--face" => {
                face_index = args
                    .next()
                    .unwrap_or_else(|| usage())
                    .parse()
                    .unwrap_or_else(|_| usage());
            }
            "--verify" => expected = Some(args.next().unwrap_or_else(|| usage())),
            _ => usage(),
        }
    }

    let data = if input.ends_with(".hex") {
        decode_hex(&fs::read_to_string(&input).unwrap_or_else(|error| fail(&error.to_string())))
    } else {
        fs::read(&input).unwrap_or_else(|error| fail(&error.to_string()))
    };
    let output = inspect(&data, face_index);

    if let Some(expected_path) = expected {
        let expected_output =
            fs::read_to_string(&expected_path).unwrap_or_else(|error| fail(&error.to_string()));
        if normalize(&output) != normalize(&expected_output) {
            eprintln!(
                "oracle output differs from {}",
                Path::new(&expected_path).display()
            );
            eprintln!("--- expected\n{}--- actual\n{}", expected_output, output);
            std::process::exit(1);
        }
    } else {
        print!("{output}");
    }
}

fn inspect(data: &[u8], face_index: u32) -> String {
    let mut output = String::new();
    line(&mut output, format_args!("schema\t1"));
    line(&mut output, format_args!("upstream\t{UPSTREAM_COMMIT}"));
    line(
        &mut output,
        format_args!(
            "collection\t{}",
            ttf_parser::fonts_in_collection(data)
                .map_or_else(|| "none".to_string(), |count| count.to_string())
        ),
    );
    line(&mut output, format_args!("face_index\t{face_index}"));

    let face = match Face::parse(data, face_index) {
        Ok(face) => face,
        Err(error) => {
            line(&mut output, format_args!("face\tinvalid\t{error:?}"));
            if let Ok(raw) = ttf_parser::RawFace::parse(data, face_index) {
                for tag in [b"cmap", b"head", b"hhea", b"hmtx", b"maxp"] {
                    let tag = Tag::from_bytes(tag);
                    let length = raw
                        .table(tag)
                        .map_or_else(|| "none".to_string(), |table| table.len().to_string());
                    line(&mut output, format_args!("raw_table\t{tag}\t{length}"));
                }
            }
            return output;
        }
    };
    line(&mut output, format_args!("face\tok"));
    line(
        &mut output,
        format_args!("units_per_em\t{}", face.units_per_em()),
    );
    let bounds = face.global_bounding_box();
    line(
        &mut output,
        format_args!(
            "bounds\t{}\t{}\t{}\t{}",
            bounds.x_min, bounds.y_min, bounds.x_max, bounds.y_max
        ),
    );
    let hhea = face.tables().hhea;
    line(
        &mut output,
        format_args!(
            "hhea\t{}\t{}\t{}",
            hhea.ascender, hhea.descender, hhea.line_gap
        ),
    );
    line(
        &mut output,
        format_args!("glyph_count\t{}", face.number_of_glyphs()),
    );

    if face.raw_face().table(Tag::from_bytes(b"OS/2")).is_some() {
        let permissions = face
            .permissions()
            .map_or_else(|| "none".to_string(), |value| format!("{value:?}"));
        line(
            &mut output,
            format_args!(
                "os2\t{}\t{}\t{:?}\t{}\t{}\t{}\t{}\t{}",
                face.weight().to_number(),
                face.width().to_number(),
                face.style(),
                face.is_bold(),
                permissions,
                face.ascender(),
                face.descender(),
                face.line_gap(),
            ),
        );
    }

    if face.raw_face().table(Tag::from_bytes(b"name")).is_some() {
        let names = face.names();
        line(&mut output, format_args!("name_count\t{}", names.len()));
        for index in 0..names.len() {
            let Some(name) = names.get(index) else {
                line(&mut output, format_args!("name\t{index}\tinvalid"));
                continue;
            };
            let decoded = name.to_string().unwrap_or_else(|| "none".to_string());
            line(
                &mut output,
                format_args!(
                    "name\t{index}\t{:?}\t{}\t{}\t{}\t{}",
                    name.platform_id, name.encoding_id, name.language_id, name.name_id, decoded,
                ),
            );
        }
    }

    if face.raw_face().table(Tag::from_bytes(b"glyf")).is_some()
        || face.raw_face().table(Tag::from_bytes(b"CFF ")).is_some()
    {
        for glyph_id in 0..face.number_of_glyphs() {
            let mut outline = RecordedOutline::default();
            if let Some(bounds) = face.outline_glyph(GlyphId(glyph_id), &mut outline) {
                line(
                    &mut output,
                    format_args!(
                        "outline\t{glyph_id}\t{}\t{}\t{}\t{}\t{}",
                        bounds.x_min, bounds.y_min, bounds.x_max, bounds.y_max, outline.commands,
                    ),
                );
            }
        }
    }

    if face.raw_face().table(Tag::from_bytes(b"CFF ")).is_some() {
        for glyph_id in 0..face.number_of_glyphs() {
            let name = face.glyph_name(GlyphId(glyph_id)).unwrap_or("none");
            line(&mut output, format_args!("glyph_name\t{glyph_id}\t{name}"));
        }
    }

    for tag in [b"cmap", b"head", b"hhea", b"hmtx", b"maxp"] {
        let tag = Tag::from_bytes(tag);
        let length = face
            .raw_face()
            .table(tag)
            .map_or_else(|| "none".to_string(), |table| table.len().to_string());
        line(&mut output, format_args!("table\t{tag}\t{length}"));
    }

    let Some(table) = face.tables().cmap else {
        line(&mut output, format_args!("cmap\tnone"));
        return output;
    };
    line(
        &mut output,
        format_args!("cmap_count\t{}", table.subtables.len()),
    );

    let mut effective_code_points = BTreeSet::new();
    for index in 0..table.subtables.len() {
        let Some(subtable) = table.subtables.get(index) else {
            line(&mut output, format_args!("subtable\t{index}\tinvalid"));
            continue;
        };
        line(
            &mut output,
            format_args!(
                "subtable\t{index}\t{:?}\t{}\t{}\t{}",
                subtable.platform_id,
                subtable.encoding_id,
                format_number(&subtable.format),
                subtable.is_unicode(),
            ),
        );
        let previous_hook = panic::take_hook();
        panic::set_hook(Box::new(|_| {}));
        let mut count = 0;
        let traversal = panic::catch_unwind(AssertUnwindSafe(|| {
            subtable.codepoints(|code_point| {
                if count >= MAX_CODE_POINT_TRAVERSAL {
                    panic::panic_any(TraversalLimit);
                }
                count += 1;
                let glyph = subtable
                    .glyph_index(code_point)
                    .map_or_else(|| "none".to_string(), |glyph| glyph.0.to_string());
                line(
                    &mut output,
                    format_args!("codepoint\t{index}\t{code_point}\t{glyph}"),
                );
                if char::from_u32(code_point).is_some() {
                    effective_code_points.insert(code_point);
                }
            });
        }));
        panic::set_hook(previous_hook);
        if let Err(payload) = traversal {
            if payload.is::<TraversalLimit>() {
                line(&mut output, format_args!("codepoints_truncated\t{index}"));
            } else {
                panic::resume_unwind(payload);
            }
        }
    }

    for code_point in effective_code_points {
        let glyph = char::from_u32(code_point)
            .and_then(|character| face.glyph_index(character))
            .map_or_else(|| "none".to_string(), |glyph| glyph.0.to_string());
        line(&mut output, format_args!("mapping\t{code_point}\t{glyph}"));
    }
    output
}

fn format_number(format: &cmap::Format<'_>) -> u16 {
    match format {
        cmap::Format::ByteEncodingTable(_) => 0,
        cmap::Format::HighByteMappingThroughTable(_) => 2,
        cmap::Format::SegmentMappingToDeltaValues(_) => 4,
        cmap::Format::TrimmedTableMapping(_) => 6,
        cmap::Format::MixedCoverage => 8,
        cmap::Format::TrimmedArray(_) => 10,
        cmap::Format::SegmentedCoverage(_) => 12,
        cmap::Format::ManyToOneRangeMappings(_) => 13,
        cmap::Format::UnicodeVariationSequences(_) => 14,
    }
}

#[derive(Default)]
struct RecordedOutline {
    commands: String,
}

impl RecordedOutline {
    fn command(&mut self, value: String) {
        if !self.commands.is_empty() {
            self.commands.push('|');
        }
        self.commands.push_str(&value);
    }
}

impl OutlineBuilder for RecordedOutline {
    fn move_to(&mut self, x: f32, y: f32) {
        self.command(format!("M {x:?} {y:?}"));
    }

    fn line_to(&mut self, x: f32, y: f32) {
        self.command(format!("L {x:?} {y:?}"));
    }

    fn quad_to(&mut self, x1: f32, y1: f32, x: f32, y: f32) {
        self.command(format!("Q {x1:?} {y1:?} {x:?} {y:?}"));
    }

    fn curve_to(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x: f32, y: f32) {
        self.command(format!("C {x1:?} {y1:?} {x2:?} {y2:?} {x:?} {y:?}"));
    }

    fn close(&mut self) {
        self.command("Z".to_string());
    }
}

fn decode_hex(source: &str) -> Vec<u8> {
    let digits: Vec<_> = source
        .chars()
        .filter(|character| character.is_ascii_hexdigit())
        .collect();
    if digits.len() % 2 != 0 {
        fail("hex fixture contains an odd number of digits");
    }
    digits
        .chunks_exact(2)
        .map(|pair| {
            let high = pair[0].to_digit(16).unwrap();
            let low = pair[1].to_digit(16).unwrap();
            ((high << 4) | low) as u8
        })
        .collect()
}

fn line(output: &mut String, arguments: std::fmt::Arguments<'_>) {
    use std::fmt::Write;
    output.write_fmt(arguments).unwrap();
    output.push('\n');
}

fn normalize(value: &str) -> &str {
    value.strip_suffix('\n').unwrap_or(value)
}

fn usage() -> ! {
    fail("usage: ttf-parser-oracle <font.ttf|fixture.hex> [--face INDEX] [--verify EXPECTED]")
}

fn fail(message: &str) -> ! {
    eprintln!("{message}");
    std::process::exit(2)
}
