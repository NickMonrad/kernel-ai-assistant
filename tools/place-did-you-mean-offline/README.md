# #2079 offline "Did you mean" tuning

Offline score sweep for `PlaceSlotMatcher` over owner-recorded lines. No app code, no audio, no device.

## Run

1. Build the private TSV (keep it outside the repo):

       python3 tools/place-did-you-mean-offline/extract_owner_tsv.py \
         --labels ~/private-acoustic-fixtures/heldout-2093-owner/owner-labels.json \
         --classes ~/private-acoustic-fixtures/heldout-2093-owner/owner-class-map.json \
         --dump-dir ~/.cache/cleanroom-2004/harness/out/owner-2093-dump \
         --out ~/private-acoustic-fixtures/place-dym-2079/owner.tsv

2. Place `nz.txt` (167 rewritable names), `impostors.txt`, `au_geonames.txt`, and `linz_locality_suburb.txt` in the same directory.

3. Run the env-gated test:

       PLACE_DYM_DIR=$HOME/private-acoustic-fixtures/place-dym-2079 \
         ./gradlew :core:skills:testDebugUnitTest \
         --tests 'com.kernel.ai.core.skills.intent.PlaceSlotMatcherOfflineScoreTest' --offline

   Output goes to `$PLACE_DYM_DIR/results.txt` (or `PLACE_DYM_OUT`). Aggregates only; do not commit it.

Without `PLACE_DYM_DIR` the test is skipped.

Results and the chosen config: see `RESULTS.md`.

## Data attribution

The AU place names in the bundled `competitor_places.txt` and the offline `au_geonames.txt` input are derived from GeoNames (https://www.geonames.org/), licensed CC BY 4.0. Attribution: "Place names © GeoNames, CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)."
