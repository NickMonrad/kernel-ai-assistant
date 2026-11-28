"""Candidate lists and respelling rules for the #2093 offline place-slot oracle.

Nothing here is tuned on recordings. The place list is general NZ place knowledge; the impostor
list is world cities, common destinations and frequent words; the respelling rules are letter-level
alternatives an English-trained CTC model is plausibly expected to emit for Māori sounds (see README).
"""

import re
import unicodedata

# Canonical display forms. Scoring uses the ASCII fold (see fold()).
NZ_PLACES = [
    "Auckland", "Wellington", "Christchurch", "Hamilton", "Tauranga", "Napier", "Hastings",
    "Nelson", "Dunedin", "Palmerston North", "Rotorua", "New Plymouth", "Whangārei", "Invercargill",
    "Whanganui", "Gisborne", "Timaru", "Oamaru", "Queenstown", "Wānaka", "Blenheim", "Greymouth",
    "Hokitika", "Westport", "Kaikōura", "Ashburton", "Masterton", "Levin", "Ōtaki", "Paraparaumu",
    "Paraparaumu Beach", "Waikanae", "Porirua", "Petone", "Lower Hutt", "Upper Hutt", "Taupō",
    "Turangi", "Taihape", "Hunterville", "Bulls", "Marton", "Feilding", "Ōhakune", "Raetihi",
    "Matamata", "Morrinsville", "Cambridge", "Te Awamutu", "Ōtorohanga", "Te Kūiti", "Pukekohe",
    "Papakura", "Manukau", "Waiuku", "Huntly", "Ngāruawāhia", "Thames", "Coromandel", "Whitianga",
    "Whakatāne", "Ōpōtiki", "Te Puke", "Mount Maunganui", "Paeroa", "Waihi", "Katikati", "Waitomo",
    "Kawerau", "Taumarunui", "Raglan", "Kaitaia", "Kerikeri", "Paihia", "Dargaville", "Warkworth",
    "Orewa", "Waiheke Island", "Helensville", "Albany", "Takapuna", "Henderson", "Karori",
    "Kilbirnie", "Newtown", "Miramar", "Eastbourne", "Wainuiomata", "Te Anau", "Gore", "Mosgiel",
    "Balclutha", "Alexandra", "Cromwell", "Arrowtown", "Franz Josef", "Hanmer Springs", "Rangiora",
    "Kaiapoi", "Rolleston", "Motueka", "Takaka", "Havelock North", "Wairoa", "Dannevirke",
    "Pahiatua", "Greytown", "Featherston", "Carterton", "Martinborough", "Kapiti", "Plimmerton",
    "Waiouru", "Hāwera", "Stratford", "Inglewood", "Ōpunake", "Foxton", "Waikato", "Te Kauwhata",
]

IMPOSTORS = [
    # World cities (foreign city names in trigger context, #2089).
    "Paris", "London", "Sydney", "Melbourne", "Tokyo", "New York", "Berlin", "Rome", "Madrid",
    "Lisbon", "Dubai", "Singapore", "Bangkok", "Beijing", "Shanghai", "Mumbai", "Delhi", "Cairo",
    "Nairobi", "Lagos", "Toronto", "Vancouver", "Chicago", "Boston", "Seattle", "Los Angeles",
    "San Francisco", "Mexico City", "Rio de Janeiro", "Buenos Aires", "Hong Kong", "Seoul", "Osaka",
    "Honolulu", "Amsterdam", "Vienna", "Prague", "Athens", "Istanbul", "Moscow", "Stockholm", "Oslo",
    "Helsinki", "Dublin", "Edinburgh", "Glasgow", "Manchester", "Liverpool", "Birmingham", "Adelaide",
    "Brisbane", "Perth", "Darwin", "Cairns", "Hobart", "Bali", "Fiji", "Tonga", "Samoa", "Hawaii",
    "Tahiti", "Las Vegas", "Ibiza", "Auckland Airport", "Sydney Harbour",
    # Common destinations and places of interest.
    "work", "home", "the airport", "the station", "school", "the beach", "the gym", "Countdown",
    "Bunnings", "Mitre 10", "Kmart", "the mall", "the hospital", "the library", "the office", "the pub",
    "the park", "town centre", "the supermarket", "the dentist", "the doctor", "the bank",
    "the post office", "the marae", "Mum's place", "the stadium",
    # Frequent words and carrier-sentence words.
    "tomorrow", "today", "weather", "timer", "calendar", "music", "recipe", "potatoes", "milk",
    "pavlova", "tyre", "pressure", "trip", "drive", "rain", "raining", "sunday", "weekend",
    "morning", "evening", "jandal", "jandals", "alarm", "torch", "shopping", "list", "reminder",
    "message", "phone", "call", "text", "tonight", "later", "now", "again", "there", "here", "where",
    "what", "when", "please", "thanks", "hello", "good morning", "navigate", "directions",
    "the weather", "the news", "a café", "a restaurant", "the bus", "the train", "the ferry",
]

# Letter-level respelling rules (match on the ASCII-folded string). Each rule's alternatives are
# tried one occurrence at a time, then all occurrences at once. Fixed before any measurement; not
# fitted to owner recordings.
RESPELL_RULES = [
    ("r", ["d", "t"]),
    ("wh", ["f", "w"]),
    ("au", ["o", "ow"]),
    ("ei", ["ay"]),
    ("u", ["oo"]),
]

VARIANT_CAP = 12


def fold(text: str) -> str:
    """ASCII fold used for all scoring: NFKD, drop combining marks, lowercase, letters/apostrophe/space only."""
    decomposed = unicodedata.normalize("NFKD", text)
    ascii_only = "".join(c for c in decomposed if not unicodedata.combining(c))
    cleaned = re.sub(r"[^a-z' ]", " ", ascii_only.lower().replace("-", " "))
    return " ".join(cleaned.split())


def _macron_variants(display: str) -> list[str]:
    # A macron vowel folds to a plain vowel; the respelling lengthens it as "ar" (ā → ar).
    base = fold(display)
    out: list[str] = []
    for i, ch in enumerate(display):
        if ch in "āēīōū":
            cut = len(fold(display[: i + 1]))
            out.append(base[:cut] + "r" + base[cut:])
    return out


def _rule_variants(folded: str) -> list[str]:
    out: list[str] = []
    for pattern, alternatives in RESPELL_RULES:
        starts = [m.start() for m in re.finditer(f"(?={re.escape(pattern)})", folded)]
        for alt in alternatives:
            for start in starts:
                out.append(folded[:start] + alt + folded[start + len(pattern):])
            if len(starts) > 1:
                out.append(re.sub(re.escape(pattern), alt, folded))
    return out


def respellings(display: str) -> list[str]:
    """Canonical fold first, then respelled variants, deduplicated and capped at VARIANT_CAP."""
    folded = fold(display)
    seen: dict[str, None] = {folded: None}
    for variant in _macron_variants(display) + _rule_variants(folded):
        seen.setdefault(variant, None)
    return list(seen)[:VARIANT_CAP]
