# #2093 pre-registration v3, amendment 4: over-cap gap takes

Context: amendment 3 (synthetic gap-fill set) fixes three takes per line, seeds `8100 + 100·take + index`, and 16 kHz output with duration ≤ 5000 ms.

Event (before any gap clip was transcribed or scored): the first full synthesis run produced R17 t1–t3, R18 t1–t3, R19 t1–t3, R20 t1–t3 (12 clips, all ≤ 5000 ms). R21 t1 (a bare-name line) rendered at 12240 ms and the script stopped on the cap. The script wrote no file for R21 t1.

Decision (recorded before generating any further clip):
- The over-cap take is EXCLUDED from the set. It is not truncated, not re-seeded and not replaced. Its record (id, duration, reason `over_cap`) stays in the manifest.
- Remaining takes continue with their pre-registered seeds. Already-valid clips (R17–R20) are kept as generated. Resume skips files that already exist; it does not regenerate them.
- Per-line counts therefore may be fewer than three takes. Any line with fewer than one valid take is reported as not evaluated for that line.
- No new seeds, no new takes beyond the pre-registered three per line, no changes to the text, the reference, the model or the cap.

Expected count: 41 clips (42 minus the excluded take), plus 60 noisy copies built from valid R21–R30 takes.

Reporting: the over-cap exclusion is reported in the README and PR as a count only, labelled as a generation failure under the pre-registered cap.

## Addendum (recorded AFTER synthesis, dumps and scoring had run; corrects counts and pins hashes; no change to the protocol)

- Actual counts: 41 valid clean takes (R21 t1 excluded); 58 noisy copies (29 valid R21–R30 sources × 10 and 5 dB). The "60 noisy" figure above was wrong and is corrected here.
- Script changes during the run (behaviour otherwise unchanged): `synth_gap.py` now resumes (existing valid files kept), records over-cap takes as `status: over_cap` in the manifest, and skips the writing of those takes. `make_noisy.py` now uses only `status == valid` sources (assert 29). `make_gap_harness.py` now uses only valid clean clips.
- Current hashes (sha256): `synth_gap.py` a9362c72…330e689ef; `make_noisy.py` e25d5f33…a379c42; `make_gap_harness.py` 1ebb776e…de27; `gap_lines.json` 2b9a5c88…c71b9c (unchanged). Amendment 3 pinned the earlier versions of the first two and the third; those pins are superseded by these.
- Scoring/oracle/asset unchanged from amendment 3.
