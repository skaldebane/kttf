package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.name.NamePlatform
import dev.skaldebane.kttf.internal.tables.name.NameRecord
import dev.skaldebane.kttf.internal.tables.name.NameTable

/** A platform associated with an OpenType name record. */
public enum class NamePlatformId {
    Unicode,
    Macintosh,
    Iso,
    Windows,
    Custom,
}

/** A resolved name-record language and region. */
public data class FontLanguage(
    public val primaryLanguage: String,
    public val region: String,
) {
    public companion object {
        public val Unknown: FontLanguage = FontLanguage("Unknown", "Unknown")
        public val EnglishUnitedStates: FontLanguage = FontLanguage("English", "United States")
    }
}

/** Common OpenType name identifiers. */
public object NameIds {
    public const val COPYRIGHT_NOTICE: Int = 0
    public const val FAMILY: Int = 1
    public const val SUBFAMILY: Int = 2
    public const val UNIQUE_ID: Int = 3
    public const val FULL_NAME: Int = 4
    public const val VERSION: Int = 5
    public const val POST_SCRIPT_NAME: Int = 6
    public const val TRADEMARK: Int = 7
    public const val MANUFACTURER: Int = 8
    public const val DESIGNER: Int = 9
    public const val DESCRIPTION: Int = 10
    public const val VENDOR_URL: Int = 11
    public const val DESIGNER_URL: Int = 12
    public const val LICENSE: Int = 13
    public const val LICENSE_URL: Int = 14
    public const val TYPOGRAPHIC_FAMILY: Int = 16
    public const val TYPOGRAPHIC_SUBFAMILY: Int = 17
    public const val COMPATIBLE_FULL: Int = 18
    public const val SAMPLE_TEXT: Int = 19
    public const val POST_SCRIPT_CID: Int = 20
    public const val WWS_FAMILY: Int = 21
    public const val WWS_SUBFAMILY: Int = 22
    public const val LIGHT_BACKGROUND_PALETTE: Int = 23
    public const val DARK_BACKGROUND_PALETTE: Int = 24
    public const val VARIATIONS_POST_SCRIPT_NAME_PREFIX: Int = 25
}

/** A binary-backed, indexed view of a face's name records. */
public class Names internal constructor(
    private val table: NameTable?,
) : Iterable<FontName> {
    public val size: Int
        get() = table?.size ?: 0

    public val isEmpty: Boolean
        get() = size == 0

    public operator fun get(index: Int): FontName? = table?.get(index)?.let(::FontName)

    override fun iterator(): Iterator<FontName> =
        object : Iterator<FontName> {
            private var index = 0
            private var next = findNext()

            override fun hasNext(): Boolean = next != null

            override fun next(): FontName {
                val value = next ?: throw NoSuchElementException()
                next = findNext()
                return value
            }

            private fun findNext(): FontName? {
                if (index >= size) return null
                return get(index++)
            }
        }
}

/** A single OpenType name record backed by the original table bytes. */
public class FontName internal constructor(
    private val record: NameRecord,
) {
    public val platformId: NamePlatformId
        get() = record.platform.toPublic()

    public val encodingId: Int
        get() = record.encodingId

    public val languageId: Int
        get() = record.languageId

    /** Resolves Windows LANGIDs and the classic Roman US-English Macintosh record. */
    public val language: FontLanguage
        get() =
            when {
                record.platform == NamePlatform.WINDOWS -> {
                    windowsLanguage(record.languageId)
                }

                record.platform == NamePlatform.MACINTOSH && record.encodingId == 0 && record.languageId == 0 -> {
                    FontLanguage.EnglishUnitedStates
                }

                else -> {
                    FontLanguage.Unknown
                }
            }

    public val nameId: Int
        get() = record.nameId

    public val isUnicode: Boolean
        get() = record.isUnicode

    public val byteLength: Int
        get() = record.data.size

    /** Decodes this record as a Unicode string. This operation allocates. */
    public fun decodeToString(): String? = record.decode()

    /** Decodes a format-1 BCP 47 language tag. This operation allocates. */
    public fun decodeLanguageTag(): String? = record.decodeLanguageTag()

    /** Copies the raw encoded name bytes into [destination]. */
    public fun copyBytesInto(
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): ByteArray {
        if (destinationOffset < 0 || destinationOffset > destination.size - byteLength) {
            throw IndexOutOfBoundsException("Destination cannot hold $byteLength bytes at offset $destinationOffset")
        }
        record.data.data.copyInto(
            destination,
            destinationOffset,
            record.data.start,
            record.data.end,
        )
        return destination
    }
}

private fun NamePlatform.toPublic(): NamePlatformId =
    when (this) {
        NamePlatform.UNICODE -> NamePlatformId.Unicode
        NamePlatform.MACINTOSH -> NamePlatformId.Macintosh
        NamePlatform.ISO -> NamePlatformId.Iso
        NamePlatform.WINDOWS -> NamePlatformId.Windows
        NamePlatform.CUSTOM -> NamePlatformId.Custom
    }

private fun windowsLanguage(id: Int): FontLanguage = WINDOWS_LANGUAGES[id] ?: FontLanguage.Unknown

private val WINDOWS_LANGUAGES: Map<Int, FontLanguage> by lazy {
    buildMap {
        for (line in WINDOWS_LANGUAGE_DATA.lineSequence()) {
            val fields = line.split('|', limit = 3)
            if (fields.size != 3) continue
            val id = fields[0].toIntOrNull(16) ?: continue
            put(id, FontLanguage(fields[1], fields[2]))
        }
    }
}

// Microsoft OpenType name-table LANGIDs, matching the pinned upstream language.rs.
private const val WINDOWS_LANGUAGE_DATA: String =
    """0000|Unknown|Unknown
0436|Afrikaans|South Africa
041C|Albanian|Albania
0484|Alsatian|France
045E|Amharic|Ethiopia
1401|Arabic|Algeria
3C01|Arabic|Bahrain
0C01|Arabic|Egypt
0801|Arabic|Iraq
2C01|Arabic|Jordan
3401|Arabic|Kuwait
3001|Arabic|Lebanon
1001|Arabic|Libya
1801|Arabic|Morocco
2001|Arabic|Oman
4001|Arabic|Qatar
0401|Arabic|Saudi Arabia
2801|Arabic|Syria
1C01|Arabic|Tunisia
3801|Arabic|U.A.E.
2401|Arabic|Yemen
042B|Armenian|Armenia
044D|Assamese|India
082C|Azeri (Cyrillic)|Azerbaijan
042C|Azeri (Latin)|Azerbaijan
046D|Bashkir|Russia
042D|Basque|Basque
0423|Belarusian|Belarus
0845|Bengali|Bangladesh
0445|Bengali|India
201A|Bosnian (Cyrillic)|Bosnia and Herzegovina
141A|Bosnian (Latin)|Bosnia and Herzegovina
047E|Breton|France
0402|Bulgarian|Bulgaria
0403|Catalan|Catalan
0C04|Chinese|Hong Kong S.A.R.
1404|Chinese|Macao S.A.R.
0804|Chinese|People's Republic of China
1004|Chinese|Singapore
0404|Chinese|Taiwan
0483|Corsican|France
041A|Croatian|Croatia
101A|Croatian (Latin)|Bosnia and Herzegovina
0405|Czech|Czech Republic
0406|Danish|Denmark
048C|Dari|Afghanistan
0465|Divehi|Maldives
0813|Dutch|Belgium
0413|Dutch|Netherlands
0C09|English|Australia
2809|English|Belize
1009|English|Canada
2409|English|Caribbean
4009|English|India
1809|English|Ireland
2009|English|Jamaica
4409|English|Malaysia
1409|English|New Zealand
3409|English|Republic of the Philippines
4809|English|Singapore
1C09|English|South Africa
2C09|English|Trinidad and Tobago
0809|English|United Kingdom
0409|English|United States
3009|English|Zimbabwe
0425|Estonian|Estonia
0438|Faroese|Faroe Islands
0464|Filipino|Philippines
040B|Finnish|Finland
080C|French|Belgium
0C0C|French|Canada
040C|French|France
140C|French|Luxembourg
180C|French|Principality of Monaco
100C|French|Switzerland
0462|Frisian|Netherlands
0456|Galician|Galician
0437|Georgian|Georgia
0C07|German|Austria
0407|German|Germany
1407|German|Liechtenstein
1007|German|Luxembourg
0807|German|Switzerland
0408|Greek|Greece
046F|Greenlandic|Greenland
0447|Gujarati|India
0468|Hausa (Latin)|Nigeria
040D|Hebrew|Israel
0439|Hindi|India
040E|Hungarian|Hungary
040F|Icelandic|Iceland
0470|Igbo|Nigeria
0421|Indonesian|Indonesia
045D|Inuktitut|Canada
085D|Inuktitut (Latin)|Canada
083C|Irish|Ireland
0434|isiXhosa|South Africa
0435|isiZulu|South Africa
0410|Italian|Italy
0810|Italian|Switzerland
0411|Japanese|Japan
044B|Kannada|India
043F|Kazakh|Kazakhstan
0453|Khmer|Cambodia
0486|K'iche|Guatemala
0487|Kinyarwanda|Rwanda
0441|Kiswahili|Kenya
0457|Konkani|India
0412|Korean|Korea
0440|Kyrgyz|Kyrgyzstan
0454|Lao|Lao P.D.R.
0426|Latvian|Latvia
0427|Lithuanian|Lithuania
082E|Lower Sorbian|Germany
046E|Luxembourgish|Luxembourg
042F|Macedonian|North Macedonia
083E|Malay|Brunei Darussalam
043E|Malay|Malaysia
044C|Malayalam|India
043A|Maltese|Malta
0481|Maori|New Zealand
047A|Mapudungun|Chile
044E|Marathi|India
047C|Mohawk|Mohawk
0450|Mongolian (Cyrillic)|Mongolia
0850|Mongolian (Traditional)|People's Republic of China
0461|Nepali|Nepal
0414|Norwegian (Bokmal)|Norway
0814|Norwegian (Nynorsk)|Norway
0482|Occitan|France
0448|Odia (formerly Oriya)|India
0463|Pashto|Afghanistan
0415|Polish|Poland
0416|Portuguese|Brazil
0816|Portuguese|Portugal
0446|Punjabi|India
046B|Quechua|Bolivia
086B|Quechua|Ecuador
0C6B|Quechua|Peru
0418|Romanian|Romania
0417|Romansh|Switzerland
0419|Russian|Russia
243B|Sami (Inari)|Finland
103B|Sami (Lule)|Norway
143B|Sami (Lule)|Sweden
0C3B|Sami (Northern)|Finland
043B|Sami (Northern)|Norway
083B|Sami (Northern)|Sweden
203B|Sami (Skolt)|Finland
183B|Sami (Southern)|Norway
1C3B|Sami (Southern)|Sweden
044F|Sanskrit|India
1C1A|Serbian (Cyrillic)|Bosnia and Herzegovina
0C1A|Serbian (Cyrillic)|Serbia
181A|Serbian (Latin)|Bosnia and Herzegovina
081A|Serbian (Latin)|Serbia
046C|Sesotho sa Leboa|South Africa
0432|Setswana|South Africa
045B|Sinhala|Sri Lanka
041B|Slovak|Slovakia
0424|Slovenian|Slovenia
2C0A|Spanish|Argentina
400A|Spanish|Bolivia
340A|Spanish|Chile
240A|Spanish|Colombia
140A|Spanish|Costa Rica
1C0A|Spanish|Dominican Republic
300A|Spanish|Ecuador
440A|Spanish|El Salvador
100A|Spanish|Guatemala
480A|Spanish|Honduras
080A|Spanish|Mexico
4C0A|Spanish|Nicaragua
180A|Spanish|Panama
3C0A|Spanish|Paraguay
280A|Spanish|Peru
500A|Spanish|Puerto Rico
0C0A|Spanish (Modern Sort)|Spain
040A|Spanish (Traditional Sort)|Spain
540A|Spanish|United States
380A|Spanish|Uruguay
200A|Spanish|Venezuela
081D|Swedish|Finland
041D|Swedish|Sweden
045A|Syriac|Syria
0428|Tajik (Cyrillic)|Tajikistan
085F|Tamazight (Latin)|Algeria
0449|Tamil|India
0444|Tatar|Russia
044A|Telugu|India
041E|Thai|Thailand
0451|Tibetan|PRC
041F|Turkish|Turkey
0442|Turkmen|Turkmenistan
0480|Uighur|PRC
0422|Ukrainian|Ukraine
042E|Upper Sorbian|Germany
0420|Urdu|Islamic Republic of Pakistan
0843|Uzbek (Cyrillic)|Uzbekistan
0443|Uzbek (Latin)|Uzbekistan
042A|Vietnamese|Vietnam
0452|Welsh|United Kingdom
0488|Wolof|Senegal
0485|Yakut|Russia
0478|Yi|PRC
046A|Yoruba|Nigeria"""
