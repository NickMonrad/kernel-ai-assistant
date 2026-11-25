# #2093 owner recordings: pre-registration v3, amendment 2

Committed before any scoring of owner audio. No owner clip has been transcribed, decoded or scored at the time of this commit. Line texts, transcripts, per-clip results and audio are private and not in git. Line IDs and classes are recorded here; line texts are not.

## What this records

1. The owner's recorded set, by line ID (36 of 50 lines).
2. Per-class recorded/total counts.
3. SHA-256 of every recorded audio file (below).
4. Lines not recorded: reported as "not recorded". No imputation, no substitution, no scoring of a neighbouring line in their place.
5. Conditions not evaluated: bare_name, and the noisy-room condition.
6. Revised small-n bounds for the recorded counts.

Base: `HELDOUT-PREREG-v3.md` (`620f356c`) and `HELDOUT-PREREG-v3-amendment-1.md` (`133baac3`). Line list sha256 `b9af729bd4be6ac66d82530012aa6f5413a1e06a0418c272880679fb8d906696` (50 rows). Frozen code and asset hashes are as in v3.

## Recording conditions

All recordings are in a quiet room. R17–R30 were not recorded: the owner stopped because the lines were repetitive, before any output existed. The noisy-room lines R21–R30 are therefore absent. No recording has a noisy-room condition.

## Recorded set (36 lines)

Recorded: R01–R16 and R31–R50. Not recorded: R17–R30 (14 lines).

| Class (glossary column) | Recorded / total | Note |
|---|---|---|
| glossary navigate | 5 / 6 | per-class ID map held privately; see audio table |
| glossary weather | 5 / 7 | |
| glossary bare_name | 0 / 2 | none (not evaluated) |
| non_glossary navigate | 3 / 7 | |
| non_glossary weather | 3 / 7 | |
| non_glossary bare_name | 0 / 1 | none (not evaluated) |
| false_boost au_town | 4 / 4 | R31–R50 block |
| false_boost foreign_city | 2 / 2 | |
| false_boost poi | 4 / 4 | |
| false_boost address | 1 / 1 | |
| false_boost nz_suburb_competitor | 2 / 2 | |
| false_boost nz_correct_place | 2 / 2 | |
| false_boost gate_trap | 4 / 4 | |
| false_boost no_place | 1 / 1 | |
| **Gold recorded** | **16 / 30** | |
| **False-boost recorded** | **20 / 20** | |

The 36 recorded IDs are those in the audio table below. Which recorded ID belongs to which class is held in the private line list and is not committed here.

Not recorded: 14 gold lines (R17–R30): glossary navigate 1, glossary weather 2, glossary bare_name 2, non_glossary navigate 4, non_glossary weather 4, non_glossary bare_name 1.

## Audio hashes (SHA-256)

| Line ID | SHA-256 |
|---|---|
| R01 | 2c9b8822505d4de17cc95da6bc4e411c57cda0aba3fc4a807435beedc27a7a97 |
| R02 | ef1d14ecdf67ca233f435525b98a2312f260fe97481c6414853ed276231f1947 |
| R03 | b6ded4e328587701dcb8a21a9551b914108a6f3dd898c1728d600bf197109ce1 |
| R04 | 52cafe172d26af88355655083e35ae70ca1599e0d57400c5092a1340d7240283 |
| R05 | 1c65be02b74cb053a5f82038db7d15af9c5f9f465e83a8d3a171456043d6b69e |
| R06 | 5c35a714315b10bb4e9f7708ce7545c7be531e07bc5480dfc31f99ca23476f7f |
| R07 | 5ed6483c160553b44e21541403d716f83e7c0baa5bf84a4f9ef09f54ab099594 |
| R08 | d04e9c1c02e1dff77353b7e9edbb3fa045b16e2e857c4629632559710b60b1bc |
| R09 | f9370ef2fc4b0e01438dabb1eb37de028744662ffa85bec6c51d070ff4ae6b90 |
| R10 | 4ee9737f0fe0009803b6751f970282cd9493449738e8cb696343598de823036e |
| R11 | 1a3b56947142cafc0f6d89921de6baea44fc948969502805369842ef5f5b6b06 |
| R12 | 44134f855ebf1d44d8669d56b03dde373e247d73656f477ed401f172ea40efb8 |
| R13 | 26b717a77e4c7cb4d97bb9e1d4551ac4aef1fc57149bc7ae4cee20873e76691b |
| R14 | af1267e55989abfdb38bbb18fb5125ec0c8ab582bcb643f13730392fdf5f3127 |
| R15 | 97dc72ab24b6d7c2ebdf3dc04bb32694a2c19f1f521aba6cbe18881226b92077 |
| R16 | bab5af293d7b0f09fe85cf3cae3fe499cfbda551860c9b85058e7680a0f1ae4b |
| R31 | 7aee01c2d807ce6a5069425656d468d73d6b605dc4c1b75c7c2985b28a6793da |
| R32 | 450adeeaaeb4709b1f2237b950184fbb284f1ebac940eaacaa9e267a5e13d511 |
| R33 | 766282ffe8afee02f494807b07c80d682d3dc57f34ecf3dd2e41c94f60335931 |
| R34 | 8cbcdd7496b24c8c8ba19cb15e9bf6b838bc2c457f642a3e5ac7b924abdb0f4c |
| R35 | c240505d5b66b762bd550bd5e222cba2a24ecc2abbd9e2105b71beaeec85faa5 |
| R36 | 36bb76dceaeb605f1be110cdbe1296781748c8373f5d21c652c0f5d512987ffe |
| R37 | d7ffb6c7baa118f1671ad87630f75afd6211ec93a86205d92332fc6179776ba1 |
| R38 | 304be0ec921378edd47c9adba9fe78608aab31bae43d762dd8b72fdfcd590301 |
| R39 | 7600413f7ac85ee6c7c5277393b93a218a5acdef4bc63e9a54683b70dfe8b957 |
| R40 | 3068018dcb488d6216eea4f481ceb9e69eaa916d408705f235ed53c915c95a2e |
| R41 | 775955c1c7bfc19e42162779932a2715e08e3c3b1e02cca0f2da4f7177bc5d34 |
| R42 | 8e4f6c70d90dbcf5d1af9bb5b957933592120df5bf4b38f918baec075323b1c5 |
| R43 | 4c931dc5db84ba822418506ad862e450e3c008127185381581112515f0da9534 |
| R44 | b19f7f20d629ac1afd28ca886028f88aecd7b648ab4e35058583f27b8f1c8443 |
| R45 | 18a03eeee33ce51b9f415943cc24e19cd5565679d7e91f8784a70fd63f221cea |
| R46 | 58758a67f642bcc9e625d0212840c9fefc1bb3bb19e6b92a07a5021f63ebbd74 |
| R47 | 2e781955205838e8a939164edcc9133ce7a19ed96c8fdf3f090a8d6725fefede |
| R48 | 67000977da2b7037b7fdcf8e66910f20148b4aadd02186ebfcba4a6988cb1621 |
| R49 | 5c6f10b22ddbd911bbacb38d733b8e03fe7b0e0a19596d6e996a70c47275c164 |
| R50 | 21f3f1700c8e1d73208bf1ef821ce6d77d738d7d94fb86f7781a1ae1779ed438 |

Each hash is of the `.m4a` file as downloaded from the owner's private Drive folder (`rclone copy`, `--include 'r*.m4a'`). The original-format files are the only audio used; conversion to 16 kHz mono is a derived step recorded in the scoring run, not a replacement.

## Conditions not evaluated

- **bare_name** (glossary 0/2, non_glossary 0/1): no recorded lines. Criterion 1 is not evaluated for bare_name. Not a pass.
- **Noisy room**: no noisy-room lines were recorded. Not evaluated.
- **Foreign homograph** (criterion 2 sub-class): no owner lines. Not evaluated.
- **Criterion 6** (scoring p95, PC timing): not owner audio. Not evaluated.

## Mapping to v3 criteria (recorded counts)

| v3 criterion | Recorded classes | n recorded | Not evaluated |
|---|---|---|---|
| 1. Gold substitutions = 0 | glossary navigate 5, glossary weather 5, non_glossary navigate 3, non_glossary weather 3 | 16 | bare_name (3 total, 0 recorded); 14 unrecorded gold lines |
| 2. Non-identity rewrites = 0 (foreign city, foreign homograph, POI, contact/address, suburb/locality, AU competitor city, gate trap) | foreign_city 2; poi 4; address 1; nz_suburb_competitor 2; au_town 4; gate_trap 4 | 17 | foreign homograph (0 lines) |
| 3. Competitor-place rewrites = 0 | au_town 4; nz_suburb_competitor 2 | 6 | none |
| 4. NZ already-correct: non-identity rewrites = 0 | nz_correct_place 2 | 2 | none |
| 5. Gate traps = 0 non-identity rewrites | gate_trap 4; no_place 1 | 5 | none |
| 6. Scoring p95 | none | 0 | all (not owner audio) |

Criteria 2, 3 and 5 overlap on au_town, nz_suburb_competitor and gate_trap lines. Criteria are not summed.

## Revised small-n bounds

For a class with zero non-identity rewrites, the one-sided 97.5% upper bound on the rate is 1 − 0.025^(1/n):

| n (recorded) | Upper bound | Classes |
|---|---|---|
| 1 | 97.5% | address, no_place |
| 2 | 84.2% | foreign_city, nz_suburb_competitor, nz_correct_place |
| 3 | 70.7% | glossary/non_glossary weather and navigate (non_glossary) |
| 4 | 60.2% | au_town, poi, gate_trap |
| 5 | 52.2% | glossary navigate, glossary weather |
| 16 (all gold, criterion 1) | 20.6% | gold substitutions pooled |

A zero count on any row above cannot show a pass for that class. A zero count is evidence only of failure to observe a rewrite in that n. Wilson 95% intervals are reported with the results at scoring time.

## Gold lines not recorded

14 gold lines (R17–R30) are reported as "not recorded". They are not imputed, substituted or scored from other lines, and they do not count in any denominator. Criterion 1 is evaluated on 16 recorded gold lines only.

## Not claimed

- No scoring has been run on owner audio at the time of this commit.
- Results from the synthetic held-out clone (v3 post-hoc) are not evidence about the owner's microphone or room.
