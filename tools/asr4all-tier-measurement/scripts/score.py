"""WER scoring helpers shared by aggregate.py.

Tokenisation matches #1978: NFC, lowercase, NFC, strip apostrophes, other punctuation separates tokens, Maori
macrons preserved. WER = summed word edit distance / summed reference words over the set.
"""

import json
import unicodedata

APOSTROPHES = "'\u2019\u2018\u02bc`\u00b4"
NZ_TERMS = ["kia ora", "jandal", "tauranga", "pūkekohe", "porirua", "petone", "whanganui", "matamata", "taupō",
            "paraparaumu", "te awamutu", "taihape", "waikato", "whakatāne", "jandals", "pavlova"]


def tokens(text: str) -> list[str]:
    t = unicodedata.normalize("NFC", unicodedata.normalize("NFC", text).lower())
    t = "".join(c for c in t if c not in APOSTROPHES)
    t = "".join(c if unicodedata.category(c)[0] in "LN" else " " for c in t)
    return t.split()


def edits(ref: list[str], hyp: list[str]) -> int:
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1]


def phrases(path: str) -> dict[str, str]:
    return {p["id"]: p["text"] for p in json.load(open(path))["phrases"]}
