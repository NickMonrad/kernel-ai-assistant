package com.kernel.ai.core.voice

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * Shared English speech text normalisation (#2115), used by Inflect Micro ([InflectMicroTextFrontend], between its
 * word overrides and its acronym and punctuation steps) and the Kokoro ExecuTorch lane (after
 * [SpeechCardText.speakable]). Both voices get the same spoken forms; engine-specific steps stay in each engine.
 *
 * Output examples:
 * - Minus signs: "-5°C" → "minus five degrees", "−5" (U+2212) → "minus five". A hyphen or minus is a sign only when a
 *   digit follows it and no letter or digit comes before it, so "203-0678" and "2026-11-26" keep their dashes.
 * - Ranges: "3-5 days" → "three to five days", "5–10 km" → "five to ten kilometres".
 * - Years: a four-digit 1000–2099 with no comma is a year only after a year cue ("in", "since", "of", "year", a month
 *   name, "founded", …), before "season" or "edition", or as one end of a year range ("from 2020 to 2026",
 *   "1990–1995"). Unlike [SherpaSpeechText], which reads any such number by its shape, a bare number stays a cardinal:
 *   "1300 people" → "one thousand three hundred people", "1999 was a great year" → "one thousand nine hundred and
 *   ninety nine was a great year". "Founded in 1999" → "founded in nineteen ninety nine"; "in 1066" → "in ten sixty
 *   six"; "the 2026 season" → "the twenty twenty six season".
 * - Big numbers: "15,000" → "fifteen thousand", "1,000,000" → "one million", "1,000.50" → "one thousand point five",
 *   "1,000,000th" → "one millionth".
 * - Money: "$49.95" → "forty nine dollars and ninety five cents", "NZ$20" → "twenty New Zealand dollars",
 *   "$12.345" → "twelve point three four five dollars", "$1.5 million" / "$1.5m" → "one point five million dollars",
 *   "$2bn" → "two billion dollars", "$100k" → "one hundred thousand dollars", "€2bn" → "two billion euros".
 * - Dates (day first, as NZ writes them): "26/11/2026", "27 November 2026" and "2026-11-26" → "the twenty sixth of
 *   November, twenty twenty six". A slash date reads month first only when day first is not a real date:
 *   "1/21/2026" → "the twenty first of January, twenty twenty six". "November 27, 2026" → "the twenty seventh of
 *   November, twenty twenty six".
 * - Times: "7:05 p.m." → "seven oh five p m", "15:00" → "three p m", "00:00" → "midnight", "8:00" → "eight o clock".
 * - Glued units and mixed tokens: "5km" → "five kilometres", "1.5km" → "one point five kilometres", "2.4GHz" → "two point
 *   four gigahertz", "0.75x" → "zero point seven five times", "4B" → "four bee", "5G" → "five gee", "NZ123" → "en zed one
 *   two three", "1080p" → "ten eighty p", "Qwen2.5" → "Qwen two point five", "Llama3.1" → "Llama three point one".
 * - Imperial units after a number: "14 mi" → "fourteen miles", "1 ft" → "one foot", "2 lb" → "two pounds", "8 oz" → "eight
 *   ounces", "100 yd" → "one hundred yards", "60 mph" → "sixty miles per hour". "in" is glued only: "12in" → "twelve inches".
 *   A spaced "in" stays a word: "They let 4 in." → "They let four in.". Feet and inches: "5 ft 10 in tall" → "five feet ten inches tall". After spelled "feet" or "foot" only "inch" or "inches" makes a height: "5 feet 10 inches tall" → "five feet ten inches tall", but "5 feet 4 in 1990" → "five feet four in nineteen ninety".
 * - Fractions with an imperial unit: "1/2 mi" → "half a mile", "1 1/2 mi" → "one and a half miles" ("mi", "ft", "lb", "oz", "yd").
 * - Bracketed conversions: [SpeechCardText] drops the bracketed unit, so "23 km (14 mi)" is spoken as "twenty three
 *   kilometres" (#2192). The screen keeps both.
 * - Decades: "the 90s" and "'90s" → "the nineties", "the 1990s" → "the nineteen nineties".
 * - Phone numbers: NZ groups are read digit by digit with "oh" for every zero, in groups: "021-123-4567" → "oh two one,
 *   one two three, four five six seven", "+64 21 123 4567" → "plus six four, two one, one two three, four five six
 *   seven". "Ring 111" → "ring one one one". "1300 555 019" and "203-0678" are grouped digit strings too.
 * - Dates and times: "7:05 p.m." → "seven oh five p m".
 * - Fractions: "1/2 cup" → "half a cup", "3/4 cup" → "three quarters of a cup", "1/8 teaspoon" → "an eighth of a teaspoon", "3/4 done" → "three quarters done", "10/10" → "ten out of ten", "50/50" → "fifty fifty", "Step 2/4" → "step two of four", "1 1/2 cups" → "one and a half cups".
 * - NZ addresses: "3/45 Queen Street" → "three, forty five Queen Street"; "Unit 3/45" → "unit three, forty five", "Flat 3/45" → "flat three, forty five".
 * - Digit strings: phone-style groups, grouped identifiers ("1300 555 019") and leading-zero or very long runs are
 *   read digit by digit, so no number is lost to a `Long` overflow.
 *
 * Output has no digits from the supported forms. Symbols such as "/", ":", "+", "#", "=", "<", ">", "€", "£" and "¥"
 * are removed only from the forms listed above.
 * Engine-specific pronunciation (identifier letter names, acronyms, punctuation) is not decided here, except that the
 * letter names used for spelled letters are [letterName].
 */
object EnglishSpeechTextNormaliser {
    private val smallCardinals = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ordinalSmall = arrayOf(
        "zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth",
    )
    private val ordinalTens = arrayOf("", "", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth")
    private val scales = arrayOf("", "thousand", "million", "billion", "trillion", "quadrillion")
    private val scaleOrdinals = arrayOf("", "thousandth", "millionth", "billionth", "trillionth", "quadrillionth")
    /**
     * Spelled-letter names that both voices say as that letter. Checked in the frame "gate five X for flight" on eSpeak
     * (Inflect, en_GB) and the Kokoro phonemis lexicons (en-us, en-gb): eSpeak reads "ay" as "eye" and "eff" as
     * "ee-eff", and phonemis returns no phonemes for "ay", "ee", "ef" or "ar" mid-sentence (the word is dropped).
     * "F" is the letter itself, which both engines read as "ef". "zed" is the NZ name for Z.
     */
    private val letterNames = mapOf(
        'A' to "eh", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "e",
        'F' to "F", 'G' to "gee", 'H' to "aitch", 'I' to "eye", 'J' to "jay",
        'K' to "kay", 'L' to "ell", 'M' to "em", 'N' to "en", 'O' to "oh",
        'P' to "pee", 'Q' to "cue", 'R' to "are", 'S' to "ess", 'T' to "tee",
        'U' to "you", 'V' to "vee", 'W' to "double you", 'X' to "ex", 'Y' to "why",
        'Z' to "zed",
    )
    private val currencyWords = mapOf("NZ" to "New Zealand", "US" to "US", "AU" to "Australian", "A" to "Australian")
    private val scaleAbbreviations = mapOf("k" to "thousand", "m" to "million", "b" to "billion", "bn" to "billion")
    private val monthNames = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val monthAlternation = (monthNames + listOf("Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Sept", "Oct", "Nov", "Dec"))
        .joinToString("|")

    /** Glued unit suffixes: singular and plural. */
    private val units = mapOf(
        "km/h" to ("kilometre per hour" to "kilometres per hour"),
        "mph" to ("mile per hour" to "miles per hour"),
        "km" to ("kilometre" to "kilometres"),
        "kms" to ("kilometre" to "kilometres"),
        "cm" to ("centimetre" to "centimetres"),
        "mm" to ("millimetre" to "millimetres"),
        "m" to ("metre" to "metres"),
        "kg" to ("kilogram" to "kilograms"),
        "mg" to ("milligram" to "milligrams"),
        "g" to ("gram" to "grams"),
        "ml" to ("millilitre" to "millilitres"),
        "mL" to ("millilitre" to "millilitres"),
        "L" to ("litre" to "litres"),
        "kW" to ("kilowatt" to "kilowatts"),
        "W" to ("watt" to "watts"),
        "GB" to ("gigabyte" to "gigabytes"),
        "MB" to ("megabyte" to "megabytes"),
        "TB" to ("terabyte" to "terabytes"),
        "kHz" to ("kilohertz" to "kilohertz"),
        "MHz" to ("megahertz" to "megahertz"),
        "GHz" to ("gigahertz" to "gigahertz"),
        "Hz" to ("hertz" to "hertz"),
        "hPa" to ("hectopascal" to "hectopascals"),
        "min" to ("minute" to "minutes"),
        "mins" to ("minute" to "minutes"),
        "hr" to ("hour" to "hours"),
        "hrs" to ("hour" to "hours"),
        "s" to ("second" to "seconds"),
        "sec" to ("second" to "seconds"),
        "secs" to ("second" to "seconds"),
        "x" to ("time" to "times"),
        "mi" to ("mile" to "miles"),
        "ft" to ("foot" to "feet"),
        "in" to ("inch" to "inches"),
        "yd" to ("yard" to "yards"),
        "yds" to ("yard" to "yards"),
        "lb" to ("pound" to "pounds"),
        "lbs" to ("pound" to "pounds"),
        "oz" to ("ounce" to "ounces"),
    )
    private val unitAlternatives = units.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val spacedUnitAlternatives = listOf(
        "km/h", "mph", "km", "kms", "cm", "mm", "kg", "mg", "ml", "mL", "kW", "GB", "MB", "TB",
        "mi", "ft", "lb", "lbs", "oz", "yd", "yds",
        "kHz", "MHz", "GHz", "Hz", "hPa", "min", "mins", "hr", "hrs", "sec", "secs",
    ).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val minusSign = Regex("""(?<![\p{L}\p{N}])[-\u2212\u2013](?=\$?\d)""")
    /** Pre-passes: "$-5" → "-$5" and "--5" → "-5", so [minusSign] reads the sign before any currency. */
    private val dollarMinus = Regex("""\$-(?=\d)""")
    private val doubleMinus = Regex("""(?<![-\p{L}\p{N}])--(?=\d)""")
    private val versionPrefix = Regex("""(?<![\p{L}\p{N}])[vV](?=\d+(?:\.\d+)+)""")
    private val hashNumber = Regex("""#(?=\d)""")
    /** "24/7" is a fixed phrase, not a date or fraction. */
    private val twentyFourSeven = Regex("""(?<![\p{N}/])24/7(?![\p{N}/])""")
    private val orMore = Regex("""(?<!\d)(\d+)\+(?=\s+[A-Za-z])""")
    private val lessThan = Regex("""(?<![\p{L}\p{N}])<(?=\s*\d)""")
    private val moreThan = Regex("""(?<![\p{L}\p{N}])>(?=\s*\d)""")
    private val digitPlus = Regex("""(?<=\d)\+(?=\d)""")
    private val digitEquals = Regex("""(?<=\d)=(?=\d)""")
    /** A leading "+" is "plus" unless it starts a "+64" country code, which [countryCodeNz] reads. */
    private val leadingPlus = Regex("""(?<![\p{L}\p{N}])\+(?!64[\s-])(?=\d)""")
    /** An NZ area code in brackets: "(09) 123 4567" → "09 123 4567", read as grouped digits. */
    private val bracketedArea = Regex("""\((0\d{1,2})\)\s*""")
    private val slashDateShortYear = Regex("""(?<![\p{N}.,:/-])(\d{1,2})/(\d{1,2})/(\d{2})(?![\p{N}/])""")
    /**
     * Day/month or fraction, read after the date and money rules. The following whitespace and word are captured so a measure
     * word can be spoken in its singular form; [expandSlashFraction] writes any other following word back as it was.
     */
    private val slashDayMonth = Regex(
        """(?<![\p{N}.,:/$])(\d{1,2})/(\d{1,2})(?![\p{N}/])(?:(\s+)([A-Za-z]+)(?!\p{L}))?""",
    )

    /** "1 1/2 cups" → "one and a half cups"; "2 1/4 tsp" → "two and a quarter tsp". Runs before [slashDayMonth]. */
    private val mixedFraction = Regex("""(?<![\p{L}\p{N}.,:/$])([1-9][0-9]{0,2})\s+([0-9]{1,2})/([0-9]{1,2})(?![\p{N}/])""")
    /** "1/2 mi" and "1 1/2 mi": an imperial abbreviation after a fraction, spelled out with it (#2192). Runs before [mixedFraction]. */
    private val fractionUnit = Regex(
        """(?<![\p{L}\p{N}.,:/$])(?:([1-9][0-9]{0,2})\s+)?([0-9]{1,2})/([0-9]{1,2})\s+(mi|ft|lbs|lb|oz|yds|yd)(?!\p{L})""",
    )
    private val flatAddress = Regex("""\b(?i:(flat|unit|apartment|suite|room)|(apt)\.?)\s+(\d+)/(\d+)\b""")

    /** NZ street address: "3/45 Queen Street" reads "three, forty five Queen Street", as "Flat 3/45" does. */
    private val streetAddress = Regex(
        """(?<![0-9./:])([0-9]{1,3})/([0-9]{1,3})(?=\s+[A-Z][\p{L}'-]*(?:\s+[A-Z][\p{L}'-]*){0,2}\s+(?:Street|St|Road|Rd|Avenue|Ave|Drive|Dr|Place|Pl|Lane|Terrace|Tce|Crescent|Cres|Way|Grove|Close|Court)\b)""",
    )
    /** A day/month after a date cue reads as a day-first date: "Meet on 26/11", "Due 5/12", "Monday 3/4". */
    private val cueDayMonth = Regex(
        """(?i)\b((?:on|by|due|from|until|till|before|after|since|between|(?:mon|tues|wednes|thurs|fri|satur|sun)day),?\s+)([0-9]{1,2})/([0-9]{1,2})(?![0-9/])""",
    )
    /** "from 3/4 to 10/4": both ends of a date range are dates. */
    private val dateRangeSlash = Regex(
        """(?i)\b((?:from|between)\s+)([0-9]{1,2})/([0-9]{1,2})(\s+(?:to|until|till|and)\s+)([0-9]{1,2})/([0-9]{1,2})(?![0-9/])""",
    )
    /** Fixed phrases, as "24/7" is: "50/50" and "9/11". They run after the date rules, so "on 9/11" is a date. */
    private val fiftyFifty = Regex("""(?<![\p{N}/])50/50(?![\p{N}/])""")
    private val nineEleven = Regex("""(?<![\p{N}/])9/11(?![\p{N}/])""")
    /** "18+ only" → "eighteen plus": a count of two or more digits with "+" reads as "plus"; "3+ people" stays "or more". */
    private val agePlus = Regex("""(?<!\d)([1-9]\d+)\+(?!\d)""")
    /** "4x4 ute" → "four by four ute"; "1920x1080" → "nineteen twenty by ten eighty". Digits on both sides only, so "0.75x" stays "times". */
    private val timesPair = Regex("""(?<![\p{L}\d.,:/$])([0-9]{1,4})[xX]([0-9]{1,4})(?![\p{L}\d.,:/])""")
    private val symbolMoney = Regex(
        """(?<![\p{L}\p{N}])([€£¥])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?:\s?(?i:(million|billion|trillion|thousand))\b(?:\s+(?i:euros?|pounds?|yen)\b)?|(?i:(bn|k|m|b))(?!\p{L}))?(?!\p{N})""",
    )
    private val perUnit = Regex("""(?<=dollars|euros|pounds|yen|cents)/(month|week|day|year|hour)\b""")
    private val countryCodeNz = Regex("""\+64[\s-]+(\d{1,2})[\s-]+(\d{3})[\s-]+(\d{3,4})(?!\p{N})""")
    private val duration = Regex("""\b(\d{1,2}):([0-5]\d):([0-5]\d)\b""")
    /** "at 14:30:00" and "by 17:00:00": a clock time with seconds after a time cue; see [expandClockWithSeconds]. */
    private val clockWithSeconds = Regex(
        """(?i)\b(at|by|until|till|from|before|after)\s+([0-9]{1,2}):([0-5][0-9]):([0-5][0-9])(?![0-9:])""",
    )
    /** "9am-5pm", "10:30–11:30". Only a time cue (colon or a.m./p.m.) makes it a time; "3-5 days" is left alone. */
    private val clockRange = Regex(
        """(?<![\p{L}\p{N}:.,/$])(\d{1,2}(?::\d{2})?)(?:\s*([AaPp])\.?\s*[Mm]\.?)?\s*[-\u2212\u2013]\s*(\d{1,2}(?::\d{2})?)(?:\s*([AaPp])\.?\s*[Mm]\.?)?(?![\p{N}:/])""",
    )
    private val ratio = Regex("""(?<![\p{N}:.,/$])(\d{1,2}):(\d)(?![\p{N}:/])""")
    private val spacedRange = Regex("""(?<![\p{L}\p{N}/:.,$])(\d+)\s+[-\u2212\u2013]\s+(\d+)(?![\p{N}/:.,])""")
    private val spacedUnit = Regex(
        """(?<![\p{L}\p{N}.,:/$])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?\s+($spacedUnitAlternatives)(?![\p{L}\p{N}/])""",
    )
    /** "5 ft 10 in tall" → "five feet ten inches tall": a height in feet and inches. Runs before the glued and spaced units, which would split it (#2192). */
    private val feetInchesAbbreviated = Regex(
        """(?<![\p{L}\p{N}.,:/$])(\d{1,2})\s*ft\s*(\d{1,2})\s*(?:inches|inch|in)(?!\p{L})""",
    )
    /** After spelled "feet" or "foot" only "inch" or "inches" makes a height: "5 feet 4 in 1990" keeps "in" as a word. */
    private val feetInchesSpelled = Regex(
        """(?<![\p{L}\p{N}.,:/$])(\d{1,2})\s*(?:feet|foot)\s*(\d{1,2})\s*inch(?:es)?(?!\p{L})""",
    )
    private val yearBeforeVerb = Regex("""(?<![\p{L}\p{N}.,:/$])((?:10|1[1-9]|20)\d{2})(?=\s+(?:was|were|is)\b)""")
    /** "a", "an" or "the" as a whole word right before a number, or before an "N to " range: "Japan 5 km" has no article. */
    private val articleBefore = Regex("""(?i)(?<!\p{L})(a|an|the)\s+(?:\d+\s+to\s+)?\z""")
    /** A word after a unit: "the 5 km walk" has one, "I ran the 10 km." does not, nor "the 10 km of road" (a function word). */
    private val wordAfter = Regex("""\A\s+(?!(?i:of|to|and|or|in|at|for|from|with|is|was)(?!\p{L}))\p{L}""")
    /** Fraction names for denominators read as words. Other denominators read "N out of D". */
    private val fractionNames = mapOf(
        2 to ("half" to "halves"), 3 to ("third" to "thirds"), 4 to ("quarter" to "quarters"),
        6 to ("sixth" to "sixths"), 8 to ("eighth" to "eighths"),
    )

    /**
     * Measure words that take "of a/an" after a fraction: "three quarters of a cup". "Half" takes no "of": "half a cup".
     * Singular only: a plural after a fraction is spoken as its singular ([singularMeasure]): "1/2 yards" → "half a yard".
     */
    private val fractionMeasures = setOf(
        "cup", "tsp", "tbsp", "teaspoon", "teaspoonful", "tablespoon",
        "litre", "liter", "ml", "kg", "g", "gram", "kilo", "lb", "pound",
        "hour", "mile", "km", "kilometre", "metre", "inch",
        "pizza", "glass", "bottle", "tank",
        "foot", "yard", "ounce",
    )
    /** Fraction measure words as a regex alternation, singular or plural: "cups", "feet", "inches". See [singularMeasure]. */
    private val fractionMeasureWords =
        "(?:(?:${fractionMeasures.joinToString("|") { Regex.escape(it) }})(?:e?s)?|feet)"
    /** A numbered place in a sequence: "Step 2/4" reads "step two of four". A measure word after the pair makes it a fraction: "Set 1/2 cup". */
    private val sequenceFraction = Regex(
        """(?i)\b(step|page|score|question|q|round|part|level|chapter|episode|game|set|rep)\s+([0-9]{1,3})/([0-9]{1,3})(?![0-9/])(?!\s+(?:$fractionMeasureWords)(?!\p{L}))""",
    )
    /**
     * What follows a slash pair that makes it a fraction, not a date: "1/2 cup", "1/2 of", "1/2 full", "from 1/2 to 3/4 cup",
     * "1/2 an hour". A bare "a", "an" or "the" does not: "On 5/12 the team meets" is a date.
     */
    private val fractionContext = Regex(
        """\A(?:\s+(?:to|and|until|till)\s+[0-9]{1,2}/[0-9]{1,2}(?![0-9/]))?\s+(?:$fractionMeasureWords|of|full|empty|power|(?:a|an)\s+(?:$fractionMeasureWords))(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val reductionVerb = Regex("""(?i)\b(?:reduce|increase|cut|divide|multiply)\b[^.!?;\n]*\z""")
    /** Words that begin with a consonant but take "an" ("an hour"). */
    private val silentHWords = setOf("hour", "hours")
    private val isoDate = Regex("""(?<![\p{N}.,:/-])(\d{4})-(\d{2})-(\d{2})(?!\p{N})""")
    private val slashDate = Regex("""(?<![\p{N}.,:/-])(\d{1,2})/(\d{1,2})/(\d{4})(?![\p{N}/])""")
    private val dayMonthYear = Regex("""\b([Tt]he\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($monthAlternation),?\s+(\d{4})(?!\p{N})""")
    private val monthDayYear = Regex("""\b($monthAlternation)\.?\s+(?:[Tt]he\s+)?(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\p{N})""")
    private val money = Regex(
        """(?<![\p{L}\p{N}])(NZ|US|AU|A)?\$(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?:\s?(?i:(million|billion|trillion|thousand))\b(?:\s+(?i:dollars?)\b)?|(?i:(bn|k|m|b))(?!\p{L}))?(?!\p{N})""",
    )
    private val emergencyCue = Regex("""(?i)\b(call|ring|dial|emergency)\s+111(?!\p{N})""")
    private val emergencyLead = Regex("""\b111(?=\s+in an emergency\b)""")
    private val countryCode = Regex("""(?<![\p{N}.,])\+64[\s-]+(?=\d)""")
    private val timeColon = Regex("""\b(\d{1,2}):(\d{2})(?:\s*([AaPp]\.?\s*[Mm]\.?))?\b""")
    private val timeSuffix = Regex("""\b(\d{1,2})\s*([AaPp]\.?\s*[Mm]\.?)\b""")
    private val resolution = Regex("""(?<![\p{L}\p{N}.,])(240|360|480|576|720|1080|1440|2160)p(?![\p{L}\p{N}])""")
    private val hyphenPhone = Regex("""(?<![\p{N}.,:/$])(0\d{2,3}|\d{4})-(\d{3})-(\d{3,4})(?!\p{N}|[.,]\p{N}|[:/-])""")
    private val hyphenPair = Regex("""\b(\d{3})-(\d{4})\b""")
    private val decade = Regex("""(?<![\p{L}\p{N}.,])((?:1[1-9]|20)\d0)s(?![\p{L}\p{N}])""")
    /** Two-digit decades need "the" or an apostrophe before them: "the 90s", "'90s", "the '80s". A bare "30s" is seconds. */
    private val shortDecade = Regex("""(?<![\p{L}\p{N}.,])((?:[Tt]he\s+)'?|')([1-9]0)s(?![\p{L}\p{N}])""")
    private val multiDot = Regex("""\b\d+(?:\.\d+){2,}\b""")
    private val ordinal = Regex("""\b(\d{1,3}(?:,\d{3})+|\d{1,18})(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    // #1486 grouped-digit identifiers: only runs of THREE OR MORE space-separated integer
    // groups are treated as a digit-sequence (e.g. "1300 555 019"). Two adjacent integers
    // such as "3 20" or "2 500" are ordinary quantities and must keep cardinal semantics.
    private val digitGroups = Regex("""\b(\d+(?:\s+\d+){2,})\b""")
    private val yearRange = Regex(
        """(?<![\p{L}\p{N}.,:/$])((?:10|1[1-9]|20)\d{2})(?:\s*[-\u2212\u2013]\s*|\s+(?:to|until|till)\s+)((?:10|1[1-9]|20)\d{2})(?!\p{N}|[.,]\p{N}|[-\u2212\u2013/:])""",
    )
    private val seasonYear = Regex("""(?<![\p{L}\p{N}.,:/$])((?:10|1[1-9]|20)\d{2})(?=\s+(?:season|edition)\b)""")
    private val range = Regex("""(?<![\p{L}\p{N}/:$\-\u2212\u2013])(?<!\d[.,])(\d+)[-\u2212\u2013](\d+)(?![\p{N}/:\-\u2212\u2013]|[.,]\p{N})""")
    /** A glued unit, optionally after a decimal: "5km", "1.5km", "2.4GHz". A decimal is never split from its unit. */
    private val gluedUnit = Regex(
        """(?<![\p{L}\p{N}.,:/$])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?($unitAlternatives)(?![\p{L}\p{N}/])""",
    )
    private val decimal = Regex("""(?<![\p{N}.,])(\d{1,3}(?:,\d{3})+|\d+)\.(\d+)(?![\p{N}]|\.\p{N})""")
    private val yearAfterCue = Regex(
        """\b(in|since|from|until|till|by|during|circa|around|before|after|of|founded|established|built|renamed|updated|released|copyright|season|born|died|edition|year|January|February|March|April|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec)\s+((?:10|1[1-9]|20)\d{2})(?!\p{N})(?![.,]\p{N})""",
        RegexOption.IGNORE_CASE,
    )
    /** A token with letters and digits, split into letter runs and digit runs: "A1B2", "Qwen3", "H2O". */
    private val mixedToken = Regex("""(?<![\p{L}0-9])(?=[\p{L}0-9]*\p{L})(?=[\p{L}0-9]*[0-9])[\p{L}0-9]+(?![\p{L}0-9])""")
    private val alnumParts = Regex("""\p{L}+|[0-9]+""")
    private val plainNumber = Regex("""(?<![\p{L}\p{N}])(\d{1,3}(?:,\d{3})+|\d+)(?![\p{L}\p{N}])""")

    /** Letter name for spelled letters ("B" → "bee"). Non-letters are returned unchanged. */
    fun letterName(letter: Char): String = letterNames[letter.uppercaseChar()] ?: letter.toString()

    fun normalize(text: String): String {
        var value = bracketedArea.replace(text) { match -> "${match.groupValues[1]} " }
        value = dollarMinus.replace(value) { "-\$" }
        value = doubleMinus.replace(value, "-")
        value = versionPrefix.replace(value, "version ")
        value = hashNumber.replace(value, "number ")
        value = twentyFourSeven.replace(value, "twenty four seven")
        value = agePlus.replace(value) { match -> "${integerWords(match.groupValues[1])} plus" }
        value = orMore.replace(value) { match -> "${match.groupValues[1]} or more" }
        value = lessThan.replace(value, "less than ")
        value = moreThan.replace(value, "more than ")
        value = digitPlus.replace(value, " plus ")
        value = digitEquals.replace(value, " equals ")
        value = leadingPlus.replace(value, "plus ")
        value = minusSign.replace(value) { "minus " }
        value = isoDate.replace(value) { match ->
            spokenDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) ?: match.value
        }
        value = slashDate.replace(value) { match ->
            spokenSlashDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) ?: match.value
        }
        value = slashDateShortYear.replace(value) { match ->
            val twoDigitYear = match.groupValues[3].toInt()
            val year = if (twoDigitYear <= 50) 2000 + twoDigitYear else 1900 + twoDigitYear
            spokenSlashDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), year) ?: match.value
        }
        value = dayMonthYear.replace(value) { match ->
            val spoken = spokenDate(
                year = match.groupValues[4].toInt(),
                month = monthNumber(match.groupValues[3]),
                day = match.groupValues[2].toInt(),
            ) ?: return@replace match.value
            if (match.groupValues[1].startsWith("T")) spoken.replaceFirstChar { it.uppercaseChar() } else spoken
        }
        value = monthDayYear.replace(value) { match ->
            spokenDate(
                year = match.groupValues[3].toInt(),
                month = monthNumber(match.groupValues[1]),
                day = match.groupValues[2].toInt(),
            ) ?: match.value
        }
        value = money.replace(value) { match -> expandMoney(match) }
        value = symbolMoney.replace(value) { match -> expandSymbolMoney(match) }
        value = perUnit.replace(value) { match -> " a ${match.groupValues[1]}" }
        value = emergencyCue.replace(value) { match -> "${match.groupValues[1]} one one one" }
        value = emergencyLead.replace(value, "one one one")
        value = countryCodeNz.replace(value) { match ->
            "plus six four, ${expandDigits(match.groupValues[1])}, ${expandDigits(match.groupValues[2])}, ${expandDigits(match.groupValues[3])}"
        }
        value = countryCode.replace(value, "plus six four, ")
        value = clockWithSeconds.replace(value) { match -> expandClockWithSeconds(match) }
        value = duration.replace(value) { match -> expandDuration(match) }
        value = clockRange.replace(value) { match -> expandClockRange(match) }
        value = timeColon.replace(value) { match -> expandTime(match) }
        value = ratio.replace(value) { match -> "${match.groupValues[1]} by ${match.groupValues[2]}" }
        value = timeSuffix.replace(value) { match ->
            val suffix = lettersOnly(match.groupValues[2]).lowercase(Locale.US)
            "${numberToWords(match.groupValues[1].toLong())} ${suffix.map { it.toString() }.joinToString(" ")}"
        }
        value = resolution.replace(value) { match -> "${yearWords(match.groupValues[1].toInt())} p" }
        value = hyphenPhone.replace(value) { match ->
            listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3]).joinToString(", ") { expandDigits(it) }
        }
        value = hyphenPair.replace(value) { match ->
            "${expandDigits(match.groupValues[1])}, ${expandDigits(match.groupValues[2])}"
        }
        value = decade.replace(value) { match -> pluralDecade(yearWords(match.groupValues[1].toInt())) }
        value = shortDecade.replace(value) { match ->
            val words = pluralDecade(cardinal(match.groupValues[2].toLong()))
            if (match.groupValues[1].startsWith("T")) "The $words" else "the $words"
        }
        value = multiDot.replace(value) { match ->
            match.value.split('.').joinToString(" point ") { integerWords(it) }
        }
        value = ordinal.replace(value) { match ->
            val number = match.groupValues[1].replace(",", "").toLongOrNull() ?: return@replace match.value
            numberToWords(number, ordinal = true)
        }
        value = flatAddress.replace(value) { match ->
            val keyword = match.groupValues[1].ifEmpty { match.groupValues[2] }.lowercase(Locale.US)
            "$keyword ${integerWords(match.groupValues[3])}, ${integerWords(match.groupValues[4])}"
        }
        value = streetAddress.replace(value) { match ->
            "${integerWords(match.groupValues[1])}, ${integerWords(match.groupValues[2])}"
        }
        value = sequenceFraction.replace(value) { match ->
            "${match.groupValues[1]} ${cardinal(match.groupValues[2].toLong())} of ${cardinal(match.groupValues[3].toLong())}"
        }
        val beforeDateCue = value
        value = dateRangeSlash.replace(beforeDateCue) { match ->
            if (isFractionContext(beforeDateCue, match)) return@replace match.value
            val from = spokenDayMonth(match.groupValues[2].toInt(), match.groupValues[3].toInt())
            val to = spokenDayMonth(match.groupValues[5].toInt(), match.groupValues[6].toInt())
            if (from == null || to == null) match.value else "${match.groupValues[1]}$from${match.groupValues[4]}$to"
        }
        val beforeCue = value
        value = cueDayMonth.replace(beforeCue) { match ->
            if (isFractionContext(beforeCue, match) || isReductionBy(beforeCue, match)) return@replace match.value
            val spoken = spokenDayMonth(match.groupValues[2].toInt(), match.groupValues[3].toInt())
            if (spoken == null) match.value else match.groupValues[1] + spoken
        }
        value = fiftyFifty.replace(value, "fifty fifty")
        value = nineEleven.replace(value, "nine eleven")
        value = fractionUnit.replace(value) { match -> expandFractionUnit(match) }
        value = mixedFraction.replace(value) { match -> expandMixedFraction(match) }
        value = slashDayMonth.replace(value) { match -> expandSlashFraction(match) }
        value = digitGroups.replace(value) { match ->
            match.value.split(Regex("\\s+")).joinToString(", ") { group -> expandDigits(group) }
        }
        value = yearRange.replace(value) { match ->
            "${yearWords(match.groupValues[1].toInt())} to ${yearWords(match.groupValues[2].toInt())}"
        }
        value = seasonYear.replace(value) { match -> yearWords(match.groupValues[1].toInt()) }
        value = yearBeforeVerb.replace(value) { match -> yearWords(match.groupValues[1].toInt()) }
        value = range.replace(value) { match -> "${match.groupValues[1]} to ${match.groupValues[2]}" }
        value = spacedRange.replace(value) { match -> "${match.groupValues[1]} to ${match.groupValues[2]}" }
        value = timesPair.replace(value) { match ->
            "${dimensionWords(match.groupValues[1])} by ${dimensionWords(match.groupValues[2])}"
        }
        value = feetInchesAbbreviated.replace(value) { match -> expandFeetInches(match) }
        value = feetInchesSpelled.replace(value) { match -> expandFeetInches(match) }
        val glued = value
        value = gluedUnit.replace(glued) { match -> expandUnit(glued, match) }
        val spaced = value
        value = spacedUnit.replace(spaced) { match -> expandUnit(spaced, match) }
        value = decimal.replace(value) { match ->
            // "Qwen2.5" → "Qwen two point five": a letter directly before the number needs a space.
            val lead = if (match.range.first > 0 && value[match.range.first - 1].isLetter()) " " else ""
            lead + spokenNumber(match.groupValues[1], match.groupValues[2])
        }
        value = yearAfterCue.replace(value) { match ->
            "${match.groupValues[1]} ${yearWords(match.groupValues[2].toInt())}"
        }
        value = mixedToken.replace(value) { match -> expandMixedToken(match) }
        value = plainNumber.replace(value) { match -> integerWords(match.groupValues[1].replace(",", "")) }
        return value
    }

    /**
     * A slash number no date or sequence rule took: "1/2" → "half", "3/4" → "three quarters", "1/8 teaspoon" → "an eighth of a
     * teaspoon", "1/2 hour" → "half an hour", "1/2 yards" → "half a yard", "10/10" → "ten out of ten". "of a/an" comes only
     * before a [fractionMeasures] word, which is spoken in its singular form. After "a" or "an" that the text already says,
     * nothing is added. Denominators outside [fractionNames] read "N out of D". A day-first date over 12 ("26/11") is still
     * read as a date. A following word that is not a measure is kept as written.
     */
    private fun expandSlashFraction(match: MatchResult): String {
        val first = match.groupValues[1].toInt()
        val second = match.groupValues[2].toInt()
        val following = match.groupValues[3] + match.groupValues[4]
        if (first > 12) spokenDayMonth(first, second)?.let { return it + following }
        if (fractionNames[second] == null || first == 0 || first >= second) {
            return "${cardinal(first.toLong())} out of ${cardinal(second.toLong())}$following"
        }
        val measure = singularMeasure(match.groupValues[4])
        return spokenFraction(first, second, measure) + if (measure == null) following else " $measure"
    }

    /** The words for a fraction with a denominator in [fractionNames]. A [measure] (singular) takes "of a/an" first. */
    private fun spokenFraction(first: Int, second: Int, measure: String?): String {
        val (single, plural) = fractionNames.getValue(second)
        val head = when {
            second == 2 -> "half"
            first == 1 -> "${indefiniteArticle(single)} $single"
            else -> "${cardinal(first.toLong())} $plural"
        }
        if (measure == null) return head
        return if (second == 2) "$head ${indefiniteArticle(measure)}" else "$head of ${indefiniteArticle(measure)}"
    }

    /**
     * The singular of a [fractionMeasures] word, or null for any other word: "cup" and "cups" → "cup", "miles" → "mile",
     * "inches" → "inch", "feet" → "foot".
     */
    private fun singularMeasure(word: String): String? {
        val lower = word.lowercase(Locale.US)
        return when {
            lower == "feet" -> "foot"
            lower in fractionMeasures -> lower
            else -> lower.removeSuffix("es").takeIf { it in fractionMeasures }
                ?: lower.removeSuffix("s").takeIf { it in fractionMeasures }
        }
    }

    /** "1 1/2 cups" → "one and a half cups": a whole number and a fraction; "a half" for halves, "two thirds" for plurals. */
    private fun expandMixedFraction(match: MatchResult): String {
        val whole = match.groupValues[1].toLong()
        val numerator = match.groupValues[2].toInt()
        val denominator = match.groupValues[3].toInt()
        val names = fractionNames[denominator]
        if (names == null || numerator == 0 || numerator >= denominator) return match.value
        val (single, plural) = names
        val fraction = when {
            denominator == 2 -> "a half"
            numerator == 1 -> "${indefiniteArticle(single)} $single"
            else -> "${cardinal(numerator.toLong())} $plural"
        }
        return "${cardinal(whole)} and $fraction"
    }

    /** "1/2 mi" → "half a mile", "1 1/2 mi" → "one and a half miles": the unit abbreviation is spelled out here (see [fractionUnit]). */
    private fun expandFractionUnit(match: MatchResult): String {
        val (singular, plural) = units.getValue(match.groupValues[4])
        if (match.groupValues[1].isNotEmpty()) {
            val mixed = expandMixedFraction(match)
            return if (mixed == match.value) match.value else "$mixed $plural"
        }
        val first = match.groupValues[2].toInt()
        val second = match.groupValues[3].toInt()
        if (first == 0 || first >= second || fractionNames[second] == null) return match.value
        return "${spokenFraction(first, second, singular)} $singular"
    }

    /** "5 ft 10 in" → "five feet ten inches"; "1 ft 1 in" → "one foot one inch". Inches of twelve or more are left as written. */
    private fun expandFeetInches(match: MatchResult): String {
        val feet = match.groupValues[1].toInt()
        val inches = match.groupValues[2].toInt()
        if (inches >= 12) return match.value
        val feetWord = if (feet == 1) "foot" else "feet"
        val inchWord = if (inches == 1) "inch" else "inches"
        return "${cardinal(feet.toLong())} $feetWord ${cardinal(inches.toLong())} $inchWord"
    }

    /** Text after a date-looking slash pair that makes it a fraction, see [fractionContext]. Checks the next 60 characters. */
    private fun isFractionContext(source: String, match: MatchResult): Boolean {
        val end = match.range.last + 1
        return fractionContext.containsMatchIn(source.substring(end, minOf(source.length, end + 60)))
    }

    /** "Reduce the sauce by 1/2": after a reduction verb in the same sentence, "by" is a quantity, not a date cue. */
    private fun isReductionBy(source: String, match: MatchResult): Boolean {
        if (!match.groupValues[1].startsWith("by", ignoreCase = true)) return false
        val start = match.range.first
        return reductionVerb.containsMatchIn(source.substring(maxOf(0, start - 80), start))
    }

    /** "the fifth of December"; null when the day and month are not a real date. */
    private fun spokenDayMonth(day: Int, month: Int): String? =
        if (month in 1..12 && isRealDay(day, month)) {
            "the ${numberToWords(day.toLong(), ordinal = true)} of ${monthNames[month - 1]}"
        } else {
            null
        }

    private fun isRealDay(day: Int, month: Int): Boolean = try {
        LocalDate.of(2000, month, day)
        true
    } catch (_: DateTimeException) {
        false
    }

    private fun indefiniteArticle(word: String): String =
        if (word.lowercase(Locale.US) in silentHWords || word.first().lowercaseChar() in "aeiou") "an" else "a"

    /**
     * A glued or spaced unit: "5km" → "five kilometres"; a unit after "a" or "an" is singular: "a 45s clip" →
     * "a forty five second clip".
     */
    private fun expandUnit(source: String, match: MatchResult): String {
        val whole = match.groupValues[1]
        val fraction = match.groupValues[2]
        val (singular, plural) = units.getValue(match.groupValues[3])
        val isOne = fraction.isEmpty() && (whole.replace(",", "") == "1" || precededByArticle(source, match))
        return "${spokenNumber(whole, fraction)} ${if (isOne) singular else plural}"
    }

    /**
     * "a 5 km" and "a 5–10 km" (read as "a 5 to 10 km") take a singular unit. "the" does so only when a word follows the
     * unit: "I ran the 10 km." stays plural, "the 5 km walk" does not.
     */
    private fun precededByArticle(source: String, match: MatchResult): Boolean {
        val start = match.range.first
        val article = articleBefore.find(source.substring(maxOf(0, start - 24), start))?.let { it.groupValues[1] }
            ?: return false
        if (!article.equals("the", ignoreCase = true)) return true
        val end = match.range.last + 1
        return wordAfter.containsMatchIn(source.substring(end, minOf(source.length, end + 24)))
    }

    /** "1:30:45" → "one hour, thirty minutes and forty five seconds"; zero parts are dropped. */
    private fun expandDuration(match: MatchResult): String {
        val parts = listOf(
            match.groupValues[1].toLong() to "hour",
            match.groupValues[2].toLong() to "minute",
            match.groupValues[3].toLong() to "second",
        ).filter { it.first > 0L }.map { (count, unit) -> "${cardinal(count)} $unit${if (count == 1L) "" else "s"}" }
        return if (parts.isEmpty()) "zero seconds" else joinList(parts)
    }

    private fun joinList(items: List<String>): String =
        if (items.size == 1) items.single() else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /** "9am-5pm" → "nine a m to five p m"; "10:30-11:30" → "ten thirty to eleven thirty". Without a cue it is not a time. */
    private fun expandClockRange(match: MatchResult): String {
        val left = match.groupValues[1]
        val leftSuffix = match.groupValues[2]
        val right = match.groupValues[3]
        val rightSuffix = match.groupValues[4]
        val hasCue = left.contains(':') || leftSuffix.isNotEmpty() || right.contains(':') || rightSuffix.isNotEmpty()
        if (!hasCue) return match.value
        return "${clockWords(left, leftSuffix)} to ${clockWords(right, rightSuffix)}"
    }

    private fun clockWords(clock: String, suffix: String): String {
        val parts = clock.split(':')
        val pieces = mutableListOf(numberToWords(parts[0].toLong()))
        if (parts.size == 2) {
            val minute = parts[1].toInt()
            if (minute != 0) pieces += if (minute < 10) listOf("oh", cardinal(minute.toLong())) else listOf(cardinal(minute.toLong()))
        }
        if (suffix.isNotEmpty()) pieces += listOf(suffix.lowercase(Locale.US), "m")
        return pieces.joinToString(" ")
    }

    /** "€30" → "thirty euros", "£4.20" → "four pounds and twenty pence", "¥1000" → "one thousand yen", "€2bn" → "two billion euros". */
    private fun expandSymbolMoney(match: MatchResult): String {
        val symbol = match.groupValues[1]
        val whole = match.groupValues[2].replace(",", "")
        val fraction = match.groupValues[3]
        val scale = scaleWord(match.groupValues[4], match.groupValues[5])
        if (symbol == "¥") return listOfNotNull(spokenNumber(whole, fraction), scale.ifEmpty { null }, "yen").joinToString(" ")
        val (unitSingular, unitPlural, subSingular, subPlural) = if (symbol == "€") {
            listOf("euro", "euros", "cent", "cents")
        } else {
            listOf("pound", "pounds", "penny", "pence")
        }
        if (scale.isNotEmpty()) return "${spokenNumber(whole, fraction)} $scale $unitPlural"
        val unitWord = if (whole.toLongOrNull() == 1L) unitSingular else unitPlural
        if (fraction.isEmpty() || fraction.all { it == '0' }) return "${integerWords(whole)} $unitWord"
        if (fraction.length > 2) return "${spokenNumber(whole, fraction)} $unitPlural"
        val cents = fraction.padEnd(2, '0').toInt()
        val centWords = "${cardinal(cents.toLong())} ${if (cents == 1) subSingular else subPlural}"
        return if (whole.toLongOrNull() == 0L) centWords else "${integerWords(whole)} $unitWord and $centWords"
    }

    /** Every digit as a word, with "oh" for zero. Only ASCII 0-9 are read; other digits (superscripts, subscripts) are skipped. */
    internal fun expandDigits(value: String): String = value.mapNotNull { digit ->
        when (digit) {
            '0' -> "oh"
            in '1'..'9' -> smallCardinals[digit - '0']
            else -> null
        }
    }.joinToString(" ")

    private fun expandMoney(match: MatchResult): String {
        val currency = currencyWords[match.groupValues[1]]?.let { "$it " }.orEmpty()
        val rawDollars = match.groupValues[2].replace(",", "")
        val fraction = match.groupValues[3]
        val scale = scaleWord(match.groupValues[4], match.groupValues[5])
        if (scale.isNotEmpty()) {
            return "${spokenNumber(rawDollars, fraction)} $scale ${currency}dollars"
        }
        val dollarWords = integerWords(rawDollars)
        val dollarUnit = if (rawDollars.toLongOrNull() == 1L) "dollar" else "dollars"
        return when {
            fraction.isEmpty() -> "$dollarWords ${currency}$dollarUnit"
            fraction.length > 2 -> "$dollarWords point ${expandDigits(fraction)} ${currency}dollars"
            else -> {
                val cents = fraction.padEnd(2, '0').toInt()
                val centWord = if (cents == 1) "cent" else "cents"
                when {
                    cents == 0 -> "$dollarWords ${currency}$dollarUnit"
                    // "$0.99" → "ninety nine cents", not "zero dollars and ninety nine cents".
                    rawDollars == "0" -> "${cardinal(cents.toLong())} $centWord"
                    else -> "$dollarWords ${currency}$dollarUnit and ${cardinal(cents.toLong())} $centWord"
                }
            }
        }
    }

    /** A number with its decimal part: "1.5" → "one point five", "1.0" → "one point oh". */
    private fun spokenNumber(whole: String, fraction: String): String {
        val words = integerWords(whole.replace(",", ""))
        if (fraction.isEmpty()) return words
        return "$words point ${expandDigits(fraction.trimEnd('0').ifEmpty { "0" })}"
    }

    /** Day first, as NZ writes dates; month first only when the day-first reading is not a real date. */
    private fun spokenSlashDate(first: Int, second: Int, year: Int): String? =
        spokenDate(year, second, first) ?: spokenDate(year, first, second)

    private fun monthNumber(name: String): Int = monthNames.indexOfFirst { it.startsWith(name.take(3), ignoreCase = true) } + 1

    /** "the twenty sixth of November, twenty twenty six"; null when the day, month and year are not a real date. */
    private fun spokenDate(year: Int, month: Int, day: Int): String? = try {
        val date = LocalDate.of(year, month, day)
        if (date.year !in 100..9999) {
            null
        } else {
            "the ${numberToWords(day.toLong(), ordinal = true)} of ${monthNames[month - 1]}, ${yearWords(year)}"
        }
    } catch (_: DateTimeException) {
        null
    }

    /** "nineties", "tens", "twenty tens": the plural of a decade's spoken year or cardinal. */
    private fun pluralDecade(words: String): String =
        if (words.endsWith("y")) "${words.dropLast(1)}ies" else "${words}s"

    /** A four-digit year as spoken: "nineteen ninety nine", "two thousand and five", "twenty twenty six". */
    internal fun yearWords(year: Int): String {
        require(year in 100..9999) { "Year out of range: $year" }
        if (year in 2000..2009) {
            val last = year % 100
            return if (last == 0) "two thousand" else "two thousand and ${cardinal(last.toLong())}"
        }
        val high = cardinal((year / 100).toLong())
        val low = year % 100
        return when {
            low == 0 -> "$high hundred"
            low < 10 -> "$high oh ${cardinal(low.toLong())}"
            else -> "$high ${cardinal(low.toLong())}"
        }
    }

    /**
     * Digits as a cardinal. Leading zeros and runs too long for a Long are read digit by digit (codes, IDs). Input with
     * no ASCII digit is returned unchanged: on Android `\d` also matches other Unicode digits, which [expandDigits] skips.
     */
    internal fun integerWords(digits: String): String {
        val ascii = digits.filter { it in '0'..'9' }
        if (ascii.isEmpty()) return digits
        val value = ascii.toLongOrNull()
        return if (value == null || (ascii.length > 1 && ascii[0] == '0')) expandDigits(ascii) else cardinal(value)
    }

    /** A width or height: four digits read as a year ("1920" → "nineteen twenty"); other digits as a cardinal. */
    private fun dimensionWords(digits: String): String =
        if (digits.length == 4 && digits[0] != '0') yearWords(digits.toInt()) else integerWords(digits)

    /** The scale word for "2bn" or "1.5 million": the spelled-out word, else the abbreviation's word, or "". */
    private fun scaleWord(word: String, abbreviation: String): String =
        word.lowercase(Locale.US).ifEmpty { scaleAbbreviations[abbreviation.lowercase(Locale.US)].orEmpty() }

    /** Letter runs are spelled ("A" → "eh", "NZ" → "en zed"); digits after letters of three or more are read digit by digit. */
    private fun expandMixedToken(match: MatchResult): String {
        val parts = mutableListOf<String>()
        var afterLetters = false
        alnumParts.findAll(match.value).forEach { part ->
            val text = part.value
            if (text[0].isLetter()) {
                parts += spelledLetters(text)
                afterLetters = true
            } else {
                // A code after letters ("NZ123", "B1234") is read digit by digit; "B12" and "1080p" keep cardinals.
                parts += if (afterLetters && text.length >= 3) expandDigits(text) else integerWords(text)
                afterLetters = false
            }
        }
        return parts.joinToString(" ")
    }

    /** Short or all-capital letter runs are spelled ("mp" → "em pee", "NZ" → "en zee"); other words stay words ("Qwen"). */
    private fun spelledLetters(letters: String): String =
        if (letters.length <= 3 || letters.all { it.isUpperCase() }) {
            letters.map { letterName(it) }.joinToString(" ")
        } else {
            letters
        }

    /**
     * "7:05 am" → "seven oh five a m"; "8:00" → "eight o clock"; "15:00" → "three p m"; "00:00" → "midnight".
     * A 24-hour time with no suffix after 12 gets "p m"; an a m or p m suffix never also says "o clock".
     */
    private fun expandTime(match: MatchResult): String = timeWords(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        lettersOnly(match.groupValues[3]).lowercase(Locale.US),
    )

    /** Clock words for a 24-hour time; [explicit] is "am" or "pm" when the text gave one. */
    private fun timeWords(hour24: Int, minute: Int, explicit: String): String {
        if (explicit.isEmpty() && hour24 == 0 && minute == 0) return "midnight"
        val (hour, suffix) = when {
            explicit.isNotEmpty() -> (hour24 % 12).let { if (it == 0) 12 else it } to explicit
            hour24 in 13..23 -> (hour24 - 12) to "pm"
            hour24 == 0 -> 12 to "am"
            else -> hour24 to ""
        }
        val pieces = mutableListOf(numberToWords(hour.toLong()))
        when {
            minute == 0 && suffix.isEmpty() -> pieces += "o clock"
            minute == 0 -> Unit
            minute < 10 -> pieces += listOf("oh", numberToWords(minute.toLong()))
            else -> pieces += numberToWords(minute.toLong())
        }
        if (suffix.isNotEmpty()) pieces += suffix.map { it.toString() }
        return pieces.joinToString(" ")
    }

    /**
     * "at 14:30:00" → "at two thirty p m": a 24-hour time with seconds after "at" (seconds dropped). After any other time
     * cue ("by", "until", "from", …) only ":00" makes a clock time; other H:MM:SS stay durations.
     */
    private fun expandClockWithSeconds(match: MatchResult): String {
        val cue = match.groupValues[1]
        val hour24 = match.groupValues[2].toInt()
        val seconds = match.groupValues[4]
        if (hour24 > 23 || (cue.lowercase(Locale.US) != "at" && seconds != "00")) return match.value
        return "$cue ${timeWords(hour24, match.groupValues[3].toInt(), "")}"
    }

    private fun lettersOnly(value: String): String = value.filter { it.isLetter() }

    internal fun numberToWords(value: Long, ordinal: Boolean = false): String {
        require(value >= 0) { "Only non-negative numbers are supported" }
        if (!ordinal) return cardinal(value)
        if (value < ordinalSmall.size) return ordinalSmall[value.toInt()]
        if (value < 100L) {
            val remainder = (value % 10).toInt()
            return if (remainder == 0) {
                ordinalTens[(value / 10).toInt()]
            } else {
                "${tens[(value / 10).toInt()]} ${ordinalSmall[remainder]}"
            }
        }
        val scale = largestScale(value)
        if (scale == 0) {
            val hundreds = value / 100
            val remainder = value % 100
            return if (remainder == 0L) {
                "${cardinal(hundreds)} hundredth"
            } else {
                "${cardinal(hundreds)} hundred and ${numberToWords(remainder, ordinal = true)}"
            }
        }
        val unit = scaleValue(scale)
        val major = value / unit
        val remainder = value % unit
        return if (remainder == 0L) {
            "${cardinal(major)} ${scaleOrdinals[scale]}"
        } else {
            val conjunction = if (remainder < 100L) " and " else " "
            "${cardinal(major)} ${scales[scale]}$conjunction${numberToWords(remainder, ordinal = true)}"
        }
    }

    private fun cardinal(value: Long): String {
        if (value < 20L) return smallCardinals[value.toInt()]
        if (value < 100L) {
            val remainder = (value % 10).toInt()
            return if (remainder == 0) {
                tens[(value / 10).toInt()]
            } else {
                "${tens[(value / 10).toInt()]} ${smallCardinals[remainder]}"
            }
        }
        val scale = largestScale(value)
        if (scale == 0) {
            val hundreds = value / 100
            val remainder = value % 100
            return if (remainder == 0L) {
                "${cardinal(hundreds)} hundred"
            } else {
                "${cardinal(hundreds)} hundred and ${cardinal(remainder)}"
            }
        }
        val unit = scaleValue(scale)
        val major = value / unit
        val remainder = value % unit
        return if (remainder == 0L) {
            "${cardinal(major)} ${scales[scale]}"
        } else {
            val conjunction = if (remainder < 100L) " and " else " "
            "${cardinal(major)} ${scales[scale]}$conjunction${cardinal(remainder)}"
        }
    }

    private fun largestScale(value: Long): Int =
        scales.indices.reversed().firstOrNull { it > 0 && value >= scaleValue(it) } ?: 0

    private fun scaleValue(index: Int): Long = 10L.pow(index * 3)

    private fun Long.pow(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result = Math.multiplyExact(result, 10L) }
        return result
    }
}
