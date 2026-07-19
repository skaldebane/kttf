package dev.skaldebane.kttf

/** A distinct reason why a font face could not be parsed. */
public enum class FontParsingError {
    MalformedFont,
    UnknownMagic,
    FaceIndexOutOfBounds,
    MissingOrMalformedHead,
    MissingOrMalformedHorizontalHeader,
    MissingOrMalformedMaximumProfile,
}

/** Thrown when the requested face cannot be parsed from the supplied tables. */
public class FontParsingException internal constructor(
    public val error: FontParsingError,
) : IllegalArgumentException(error.message())

private fun FontParsingError.message(): String =
    when (this) {
        FontParsingError.MalformedFont -> "Malformed font"
        FontParsingError.UnknownMagic -> "Unknown font magic"
        FontParsingError.FaceIndexOutOfBounds -> "Face index is out of bounds"
        FontParsingError.MissingOrMalformedHead -> "The head table is missing or malformed"
        FontParsingError.MissingOrMalformedHorizontalHeader -> "The hhea table is missing or malformed"
        FontParsingError.MissingOrMalformedMaximumProfile -> "The maxp table is missing or malformed"
    }
