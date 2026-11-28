"""Build the place candidate list: NZ from LINZ NZ Place Names (layer 51681), CC BY 4.0; optionally AU competitors and homograph names from GeoNames (CC BY 4.0; see the GEONAMES_* constants).

Attribution: "NZ Place Names (layer 51681), LINZ, CC BY 4.0". Every output row carries it.

Two sources, pick one:

  --from-file PATH   A locally downloaded export of layer 51681 (GeoJSON FeatureCollection, a JSON
                     list of features, or CSV with a header row). No key needed. Preferred when the
                     owner has downloaded the layer by hand.
  (default)          LINZ Data Service WFS. Needs LINZ_API_KEY in the environment. The key is never
                     printed or written.

Workflow: run with `--discover` first to see the property names and values (values only), then
build with explicit `--name-field`, `--type-field` and `--types`. The feature-type filter is
required: no default. Output is private by default.

The WFS layer name and property names were NOT verified against a live service (no key here).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

import lexicon

LAYER = "layer-51681"
ENDPOINT = "https://data.linz.govt.nz/services/wfs"  # key is sent in a header, never in the URL
KEY_HEADER = "X-API-Key"  # unverified: no key in this environment; check against LINZ docs before use
ATTRIBUTION = "NZ Place Names (layer 51681), LINZ, CC BY 4.0"
LICENCE_TEXT = "Contains data sourced from the LINZ Data Service and licensed for reuse under CC BY 4.0"
LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/"
EXPORT_DATE = "2026-11-23"  # stated by the owner for the CSV download; not read from the file
PAGE = 1000

# GeoNames (CC BY 4.0, https://www.geonames.org/; readme in the dump). Used for two purposes only:
#   AU competitors: populated places of Australia (feature class P, codes below). Never rewritten to.
#   Homograph names: GeoNames cities15000 names outside NZ and AU. Used only to flag NZ rewritable rows.
GEONAMES_ATTRIBUTION = "GeoNames (geonames.org), CC BY 4.0"
GEONAMES_LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/"
GEONAMES_URL_AU = "https://download.geonames.org/export/dump/AU.zip"
GEONAMES_URL_CITIES = "https://download.geonames.org/export/dump/cities15000.zip"
GEONAMES_URL_README = "https://download.geonames.org/export/dump/readme.txt"
# PPLQ (abandoned), PPLW (destroyed), PPLH (historical) and PPLF (farm) are excluded.
GEONAMES_AU_CODES = ("PPL", "PPLX", "PPLL", "PPLA", "PPLA2", "PPLC", "PPLS", "STLMT")
GEONAMES_COL = {"name": 1, "lat": 4, "lon": 5, "class": 6, "code": 7, "country": 8}


# ---------------------------------------------------------------- sources

def fetch_page(key: str, start: int) -> dict:
    query = urllib.parse.urlencode({
        "service": "WFS", "version": "2.0.0", "request": "GetFeature", "typeNames": LAYER,
        "outputFormat": "application/json", "count": PAGE, "startIndex": start,
    })
    req = urllib.request.Request(ENDPOINT + "?" + query, headers={KEY_HEADER: key})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def features_from_api(key: str) -> list[dict]:
    feats: list[dict] = []
    start = 0
    while True:
        page = fetch_page(key, start).get("features", [])
        feats.extend(page)
        if len(page) < PAGE:
            return feats
        start += PAGE


def features_from_file(path: str) -> list[dict]:
    """GeoJSON, JSON feature list, or CSV. CSV rows become features with flat properties.

    CSV lat/lon columns, if present (named lat/latitude and lon/longitude/lng, any case), set the
    point; otherwise geometry is None.
    """
    if path.lower().endswith(".csv"):
        with open(path, encoding="utf-8-sig", newline="") as fh:
            out = []
            for row in csv.DictReader(fh):
                lat = _pick(row, ("lat", "latitude", "crd_latitude"))
                lon = _pick(row, ("lon", "lng", "longitude", "crd_longitude"))
                geom = None
                if lat and lon:
                    geom = {"type": "Point", "coordinates": [float(lon), float(lat)]}
                out.append({"type": "Feature", "properties": row, "geometry": geom})
            return out
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    if isinstance(data, dict) and "features" in data:
        return data["features"]
    if isinstance(data, list):
        return data
    raise SystemExit(f"{path}: expected GeoJSON FeatureCollection, a feature list, or CSV")


def _pick(row: dict, names: tuple[str, ...]) -> str | None:
    lowered = {k.lower(): v for k, v in row.items() if k}
    for n in names:
        if lowered.get(n):
            return lowered[n]
    return None


# ---------------------------------------------------------------- discover / build

def discover(features: list[dict]) -> None:
    if not features:
        print("no features", file=sys.stderr)
        return
    props = features[0].get("properties", {}) or {}
    print("properties:", ", ".join(sorted(props)))
    counts: dict[str, Counter] = {}
    for f in features:
        for k, v in (f.get("properties") or {}).items():
            if isinstance(v, (str, int)) and v not in (None, ""):
                counts.setdefault(k, Counter())[str(v)] += 1
    for k in sorted(counts):
        distinct = len(counts[k])
        if distinct <= 40:
            print(f"{k} ({distinct} distinct):", dict(counts[k].most_common()))
        else:
            print(f"{k}: {distinct} distinct (not listed)")


# Rewritable set = Town and City, plus every glossary place (matched by folded spelling). Every other
# kept type is a competitor: it can win the decision and cause an abstain, but is never rewritten to.
REWRITABLE_TYPES = {"Town", "City"}
PRIORITY = {"City": 3, "Town": 2, "Village": 1}  # tie-break prior: larger place first


def build(features: list[dict], name_field: str, type_field: str, types: set[str],
          glossary: set[str], status_field: str | None = None,
          exclude_status: tuple[str, ...] = ()) -> list[dict]:
    rows = []
    for f in features:
        p = f.get("properties") or {}
        t = str(p.get(type_field))
        if t not in types:
            continue
        if status_field and any(s in str(p.get(status_field) or "") for s in exclude_status):
            continue
        name = p.get(name_field)
        if not name:
            continue
        name = str(name)
        rewritable = t in REWRITABLE_TYPES or lexicon.fold(name) in glossary
        geom = f.get("geometry") or {}
        lon = lat = None
        if geom.get("type") == "Point":
            lon, lat = geom["coordinates"][:2]
        rows.append({"name": name, "type": t, "role": "rewritable" if rewritable else "competitor",
                     "prio": PRIORITY.get(t, 0), "lat": lat, "lon": lon, "attribution": ATTRIBUTION})
    return merge(rows)


def merge(rows: list[dict], foreign_keys: set[str] = frozenset()) -> list[dict]:
    """One row per name. A name keeps its rewritable row if it has one, so NZ rewritable wins over AU.
    A kept rewritable row whose folded name is a GeoNames AU competitor name, or in foreign_keys, is flagged
    homograph (exact-match veto)."""
    homo = {lexicon.fold(r["name"]) for r in rows if r.get("attribution") == GEONAMES_ATTRIBUTION} | set(foreign_keys)
    rows = sorted(rows, key=lambda r: r["role"] != "rewritable")
    seen: dict[str, dict] = {}
    for r in rows:
        seen.setdefault(r["name"], r)
    out = []
    for r in seen.values():
        if r["role"] == "rewritable" and lexicon.fold(r["name"]) in homo:
            r = {**r, "homograph": True}
        out.append(r)
    return sorted(out, key=lambda r: (r["role"], r["name"]))


def geonames_rows(path: Path):
    with path.open(encoding="utf-8") as fh:
        yield from csv.reader(fh, delimiter="\t", quoting=csv.QUOTE_NONE)


def geonames_au_rows(path: Path) -> list[dict]:
    """GeoNames AU populated places (class P, codes in GEONAMES_AU_CODES). Always competitors."""
    c_ = GEONAMES_COL
    rows = []
    for c in geonames_rows(path):
        if len(c) <= c_["country"] or c[c_["country"]] != "AU" or c[c_["class"]] != "P":
            continue
        if c[c_["code"]] not in GEONAMES_AU_CODES or not c[c_["name"]]:
            continue
        rows.append({"name": c[c_["name"]], "type": c[c_["code"]], "role": "competitor", "prio": 0,
                     "lat": float(c[c_["lat"]]), "lon": float(c[c_["lon"]]), "attribution": GEONAMES_ATTRIBUTION})
    return rows


def geonames_foreign_keys(path: Path) -> set[str]:
    """Folded names of GeoNames cities outside NZ and AU. Used only to flag NZ rewritable rows (homograph veto)."""
    c_ = GEONAMES_COL
    return {lexicon.fold(c[c_["name"]]) for c in geonames_rows(path)
            if len(c) > c_["country"] and c[c_["country"]] not in ("NZ", "AU") and c[c_["name"]]}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--from-file", help="local export of layer 51681 (GeoJSON, JSON list or CSV)")
    src.add_argument("--api", action="store_true", help="use the WFS API with LINZ_API_KEY")
    ap.add_argument("--discover", action="store_true")
    ap.add_argument("--name-field")
    ap.add_argument("--type-field")
    ap.add_argument("--types", help="comma-separated type values to keep (required for a build)")
    ap.add_argument("--status-field", help="property to test with --exclude-status (e.g. status)")
    ap.add_argument("--exclude-status", help="comma-separated substrings; rows whose status field "
                                             "contains any are dropped (e.g. Replaced,Discontinued)")
    ap.add_argument("--glossary-places", type=Path,
                    help="text file, one glossary place per line; these names are rewritable")
    ap.add_argument("--out", help="output JSON path (required for a build)")
    ap.add_argument("--geonames-au", type=Path, help="GeoNames AU.txt: AU populated places as competitors")
    ap.add_argument("--geonames-foreign", type=Path, help="GeoNames cities15000.txt: foreign names for the homograph flag")
    ap.add_argument("--geonames-retrieved", help="UTC retrieval time of the GeoNames dumps, recorded in the header")
    args = ap.parse_args(argv)

    if args.from_file:
        features = features_from_file(args.from_file)
    elif args.api:
        key = os.environ.get("LINZ_API_KEY")
        if not key:
            print("LINZ_API_KEY is not set; use --from-file with a local export.", file=sys.stderr)
            return 2
        features = features_from_api(key)
    else:
        ap.error("choose a source: --from-file PATH or --api")
    print(f"loaded {len(features)} features", file=sys.stderr)

    if args.discover:
        discover(features)
        return 0
    if not (args.name_field and args.type_field and args.types and args.out):
        ap.error("a build needs --name-field, --type-field, --types and --out (run --discover first)")
    exclude = tuple(s for s in (args.exclude_status or "").split(",") if s)
    glossary = set()
    if args.glossary_places:
        glossary = {lexicon.fold(line.strip()) for line in args.glossary_places.read_text(encoding="utf-8")
                    .splitlines() if line.strip() and not line.startswith("#")}
    rows = build(features, args.name_field, args.type_field, set(args.types.split(",")), glossary,
                 args.status_field, exclude)
    matched = {lexicon.fold(r["name"]) for r in rows} & glossary
    if glossary - matched:
        print(f"glossary names with no LINZ row in these types: {sorted(glossary - matched)}", file=sys.stderr)
    source_sha256 = hashlib.sha256(Path(args.from_file).read_bytes()).hexdigest() if args.from_file else None
    header = {
        "source": "NZ Place Names, LINZ Data Service, " + LAYER,
        "licence": LICENCE_TEXT,
        "licence_url": LICENCE_URL,
        "export_date": EXPORT_DATE,
        "source_sha256": source_sha256,
        "changes": ("filtered to the configured feature types, excluding the configured statuses; "
                    "reduced to name, type, role, priority, coordinates and a per-row attribution; "
                    "names stored as exported (macrons kept); folding to ASCII and respellings applied at scoring time, not here"),
        "generator": "build_place_candidates.py",
    }
    homograph_extra = geonames_foreign_keys(args.geonames_foreign) if args.geonames_foreign else set()
    au = geonames_au_rows(args.geonames_au) if args.geonames_au else []
    rows = merge(rows + au, homograph_extra)
    if args.geonames_au or args.geonames_foreign:
        header["geonames"] = {
            "attribution": GEONAMES_ATTRIBUTION,
            "licence_url": GEONAMES_LICENCE_URL,
            "source_urls": {"AU": GEONAMES_URL_AU, "cities15000": GEONAMES_URL_CITIES, "readme": GEONAMES_URL_README},
            "retrieved_utc": args.geonames_retrieved,
            "au_file_sha256": hashlib.sha256(args.geonames_au.read_bytes()).hexdigest() if args.geonames_au else None,
            "foreign_file_sha256": (hashlib.sha256(args.geonames_foreign.read_bytes()).hexdigest()
                                    if args.geonames_foreign else None),
            "au_codes": list(GEONAMES_AU_CODES),
            "competitor_rows": len(au),
            "homograph_names": len(homograph_extra),
            "changes": ("AU: class P, country AU, codes above; competitors only. Foreign: names outside NZ and AU; "
                        "used only to flag NZ rewritable rows (homograph); not written to places."),
        }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump({"header": header, "places": rows}, fh, ensure_ascii=False, indent=1)
    print(f"wrote {len(rows)} places to {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
