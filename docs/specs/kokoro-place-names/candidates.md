# Kokoro place-name candidate respellings (#2193)

Candidate respellings for NZ place names, for the owner's by-ear pick on the S23U (Kokoro Emma, `bf_emma`).
No lexicon entries are added here. Once a candidate is chosen, the pick goes into `pronunciation_lexicon.json` in a follow-up.

## How the candidates were checked

- Each respelling was run through the host en-gb phonemis runner in the app's text path: the shipped `PronunciationLexicon` is applied, then macrons are folded, then hyphen and slash joins are split, then phonemis runs.
- `n=1` is the shipped respelling where the name already has a lexicon entry. Candidates start at `n=2`.
- **Phonemis note.** A piece listed as *fallback* has no phonemis entry, so the app's #2187 letter-to-sound fallback decides its sound. The rendered audio, not the phoneme string, is the evidence for those candidates.
- Audio, transcripts, and per-render reports are kept outside the repo and are not committed.
- Hastings is not in the list: it is standard English and was already covered by the #2175 phrases.

## Candidates

| Name | n | Respelling | Shipped | Phonemis note |
|---|---|---|---|---|
| Petone | 1 | Pih-toe-nee | yes | fallback: Pih |
| Petone | 2 | Pet-own-ee |  | phonemis reads all pieces |
| Petone | 3 | Pit-oh-nee |  | phonemis reads all pieces |
| Pūkekohe | 1 | Pook-ee-koh-ee | yes | fallback: koh |
| Pūkekohe | 2 | Pook-ay-hoe-hay |  | fallback: ay |
| Pūkekohe | 3 | Pook-ee-hoe-ee |  | phonemis reads all pieces |
| Whakatāne | 1 | Fukka-tarn-ee | yes | fallback: Fukka |
| Whakatāne | 2 | Fah-ka-tay-nee |  | phonemis reads all pieces |
| Whakatāne | 3 | Wah-ka-tar-nee |  | phonemis reads all pieces |
| Te Awamutu | 1 | Tee Ah-wa-moo-too | yes | phonemis reads all pieces |
| Te Awamutu | 2 | Tay Ah-wa-moo-too |  | phonemis reads all pieces |
| Te Awamutu | 3 | Tee Ah-wah-moo-too |  | phonemis reads all pieces |
| Porirua | 1 | Pore-ee-roo-uh | yes | phonemis reads all pieces |
| Porirua | 2 | Po-ree-roo-ah |  | phonemis reads all pieces |
| Porirua | 3 | Pore-ee-roo-ah |  | phonemis reads all pieces |
| Matamata | 1 | Mah-tah-mah-tah | yes | fallback: tah, tah |
| Matamata | 2 | Mat-a-mat-a |  | phonemis reads all pieces |
| Matamata | 3 | Mat-ay-mat-ay |  | fallback: ay, ay |
| Taupō | 1 | Toe-paw | yes | phonemis reads all pieces |
| Taupō | 2 | Tow-paw |  | phonemis reads all pieces |
| Taupō | 3 | Tau-paw |  | phonemis reads all pieces |
| Whanganui | 1 | Wonga-noo-ee | yes | fallback: noo |
| Whanganui | 2 | Wang-a-new-ee |  | fallback: Wang |
| Whanganui | 3 | Wong-a-new-ee |  | fallback: Wong |
| Tauranga | 1 | Toe-rung-ah | yes | phonemis reads all pieces |
| Tauranga | 2 | Tow-rang-ah |  | phonemis reads all pieces |
| Tauranga | 3 | Tor-rung-ah |  | phonemis reads all pieces |
| Paraparaumu | 1 | Para-puh-ram | yes | fallback: puh |
| Paraparaumu | 2 | Para-pa-rah-moo |  | phonemis reads all pieces |
| Paraparaumu | 3 | Para-pa-ram-oo |  | fallback: oo |
| Taihape | 1 | Tie-happy | yes | phonemis reads all pieces |
| Taihape | 2 | Tie-hup-ee |  | phonemis reads all pieces |
| Taihape | 3 | Tie-hah-pay |  | phonemis reads all pieces |
| Waikato | 1 | Why-kat-o | yes | fallback: kat |
| Waikato | 2 | Why-ka-toe |  | phonemis reads all pieces |
| Waikato | 3 | Why-kay-toe |  | phonemis reads all pieces |
| Jandal | 1 | Jandle | yes | fallback: Jandle |
| Jandal | 2 | Jan-dull |  | fallback: Jan |
| Jandal | 3 | Jand-ul |  | fallback: Jand, ul |
| Kia ora | 1 | Kee-ora | yes | phonemis reads all pieces |
| Kia ora | 2 | Kye-or-ah |  | phonemis reads all pieces |
| Kia ora | 3 | Kee-ur-ah |  | phonemis reads all pieces |
| mahi | 1 | ma-hee | yes | phonemis reads all pieces |
| mahi | 2 | mah-hee |  | phonemis reads all pieces |
| mahi | 3 | mah-hay |  | phonemis reads all pieces |
| Ruapehu | 1 | Roo-ah-pay-hoe |  | phonemis reads all pieces |
| Ruapehu | 2 | Roo-a-pie-hoe |  | phonemis reads all pieces |
| Ohakune | 1 | Oh-ha-kew-nee |  | fallback: kew |
| Ohakune | 2 | Oh-hah-moo-nay |  | phonemis reads all pieces |
| Tongariro | 1 | Tong-a-ree-roe |  | phonemis reads all pieces |
| Tongariro | 2 | Tong-a-rear-oh |  | phonemis reads all pieces |
| Rotorua | 1 | Roe-tor-roo-ah |  | phonemis reads all pieces |
| Rotorua | 2 | Roe-tay-roo-ah |  | phonemis reads all pieces |
| Paekākāriki | 1 | Pie-ka-ka-ree-kee |  | phonemis reads all pieces |
| Paekākāriki | 2 | Pay-ka-ka-ree-kee |  | phonemis reads all pieces |
| Paekākāriki | 3 | Pie-ka-kay-ree-kee |  | phonemis reads all pieces |
| Whangārei | 1 | Fang-ah-ray |  | phonemis reads all pieces |
| Whangārei | 2 | Fang-ee-ray |  | phonemis reads all pieces |
| Ngāruawāhia | 1 | Nah-roo-ah-fee-ah |  | phonemis reads all pieces |
| Ngāruawāhia | 2 | Nah-roo-ah-wah-hee-ah |  | phonemis reads all pieces |
| Remutaka | 1 | Rem-yoo-ta-ka |  | fallback: yoo |
| Remutaka | 2 | Rem-ee-ta-ka |  | phonemis reads all pieces |
| Ōtautahi | 1 | Oh-tow-ta-hee |  | phonemis reads all pieces |
| Ōtautahi | 2 | Oh-tau-ta-hee |  | phonemis reads all pieces |
| Aotearoa | 1 | Ow-tay-ah-row |  | phonemis reads all pieces |
| Aotearoa | 2 | Ow-tee-ah-row |  | phonemis reads all pieces |
| whānau | 1 | Fah-now |  | phonemis reads all pieces |
| whānau | 2 | Far-now |  | phonemis reads all pieces |
| Māori | 1 | Mow-ree |  | phonemis reads all pieces |
| Māori | 2 | Mah-ree |  | phonemis reads all pieces |
| Māori | 3 | Mao-ree |  | phonemis reads all pieces |
| Te Papa | 1 | Tay Pah-pah |  | phonemis reads all pieces |
| Te Papa | 2 | Tee Pah-pah |  | phonemis reads all pieces |
| Te Papa | 3 | Tay Pa-pa |  | phonemis reads all pieces |
| Kāpiti | 1 | Ka-pee-tee |  | phonemis reads all pieces |
| Kāpiti | 2 | Ka-pit-ee |  | phonemis reads all pieces |
| Ōtaki | 1 | Oh-ta-kee |  | phonemis reads all pieces |
| Ōtaki | 2 | Oh-tay-kee |  | phonemis reads all pieces |
| Taumarunui | 1 | Tow-mah-roo-new-ee |  | phonemis reads all pieces |
| Taumarunui | 2 | Tow-ma-roo-nee |  | phonemis reads all pieces |
| Mangawhai | 1 | Mong-a-why |  | phonemis reads all pieces |
| Mangawhai | 2 | Mong-a-wye |  | phonemis reads all pieces |
| Wairoa | 1 | Why-row-a |  | phonemis reads all pieces |
| Wairoa | 2 | Wy-row-ah |  | fallback: Wy |
| Hokitika | 1 | Hoe-kee-tee-ka |  | phonemis reads all pieces |
| Hokitika | 2 | Hoe-kee-tay-ka |  | phonemis reads all pieces |
| Invercargill | 1 | In-ver-car-gill |  | phonemis reads all pieces |
| Invercargill | 2 | In-ver-car-gull |  | phonemis reads all pieces |
| Kaikōura | 1 | Kye-kow-rah |  | fallback: kow |
| Kaikōura | 2 | Kai-kow-ra |  | fallback: kow |
| Waitākere | 1 | Why-ta-kay-ree |  | phonemis reads all pieces |
| Waitākere | 2 | Why-tay-kay-ree |  | phonemis reads all pieces |
| Tāmaki Makaurau | 1 | Tah-mah-kee Mah-kow-ra-oo |  | fallback: Tah, kow, oo |
| Tāmaki Makaurau | 2 | Tam-a-kee Mak-ow-ra-oo |  | fallback: Mak, oo |
| Manawatū | 1 | Man-a-wa-too |  | phonemis reads all pieces |
| Manawatū | 2 | Mah-nah-wah-too |  | phonemis reads all pieces |
| Taranaki | 1 | Tar-a-nah-kee |  | phonemis reads all pieces |
| Taranaki | 2 | Tara-nah-kee |  | phonemis reads all pieces |
| Mount Maunganui | 1 | Mount Mow-nga-nee |  | fallback: nga |
| Mount Maunganui | 2 | Mount Maun-ga-nee |  | fallback: ga |
| Ōpōtiki | 1 | Oh-poh-tee-kee |  | phonemis reads all pieces |
| Ōpōtiki | 2 | Op-oh-tee-kee |  | phonemis reads all pieces |
| Ōpōtiki | 3 | Oh-po-tik-ee |  | phonemis reads all pieces |
| Tūrangi | 1 | Too-rang-ee |  | phonemis reads all pieces |
| Tūrangi | 2 | Too-rang-eye |  | phonemis reads all pieces |
| Tokoroa | 1 | Toe-ka-roe-a |  | phonemis reads all pieces |
| Tokoroa | 2 | Tow-ka-roe-a |  | phonemis reads all pieces |
| Te Puke | 1 | Tay Pook-ee |  | phonemis reads all pieces |
| Te Puke | 2 | Tay Pook |  | phonemis reads all pieces |
| Pāpāmoa | 1 | Pah-pah-moa |  | phonemis reads all pieces |
| Pāpāmoa | 2 | Pah-pah-mo-ah |  | phonemis reads all pieces |
| Waiheke | 1 | Why-heck-ee |  | phonemis reads all pieces |
| Waiheke | 2 | Why-hake-ee |  | phonemis reads all pieces |
| Kerikeri | 1 | Ker-ee-ker-ee |  | phonemis reads all pieces |
| Kerikeri | 2 | Kerry-kerry |  | phonemis reads all pieces |
| Kaitaia | 1 | Kye-tie-a |  | phonemis reads all pieces |
| Kaitaia | 2 | Kye-tie-ah |  | phonemis reads all pieces |
| Coromandel | 1 | Core-oh-man-dull |  | phonemis reads all pieces |
| Coromandel | 2 | Core-oh-man-dell |  | phonemis reads all pieces |
| Waitaki | 1 | Why-ta-kee |  | phonemis reads all pieces |
| Waitaki | 2 | Why-tay-kee |  | phonemis reads all pieces |
| Ōamaru | 1 | Oh-ah-mah-roo |  | phonemis reads all pieces |
| Ōamaru | 2 | Oh-a-ma-roo |  | phonemis reads all pieces |
| Whakarewarewa | 1 | Fah-ka-ray-wah-ray-wah |  | phonemis reads all pieces |
| Whakarewarewa | 2 | Fah-ka-ree-wah-ree-wah |  | phonemis reads all pieces |
| Hāwera | 1 | Hah-wer-a |  | phonemis reads all pieces |
| Hāwera | 2 | Hah-wear-a |  | phonemis reads all pieces |
| Hāwera | 3 | Hah-wer-ah |  | phonemis reads all pieces |
| Rangiora | 1 | Rang-ee-or-ah |  | phonemis reads all pieces |
| Rangiora | 2 | Rung-ee-or-a |  | phonemis reads all pieces |
| Rangiora | 3 | Ran-gee-or-ah |  | phonemis reads all pieces |
| Waiouru | 1 | Why-oh-roo |  | phonemis reads all pieces |
| Waiouru | 2 | Why-oh-rue |  | phonemis reads all pieces |
| Mataura | 1 | Mah-tow-rah |  | phonemis reads all pieces |
| Mataura | 2 | Mat-ow-ra |  | phonemis reads all pieces |
| Ōhope | 1 | Oh-hope |  | phonemis reads all pieces |
| Ōhope | 2 | Oh-hoe-pee |  | phonemis reads all pieces |
| Rangitīkei | 1 | Rang-ee-tee-kay |  | phonemis reads all pieces |
| Rangitīkei | 2 | Rung-ee-tee-kay |  | phonemis reads all pieces |
| Rangitīkei | 3 | Rang-ee-tee-kye |  | phonemis reads all pieces |
| Waipukurau | 1 | Why-pook-oo-rah |  | fallback: oo |
| Waipukurau | 2 | Why-pook-ow-rah |  | phonemis reads all pieces |

Totals: 60 names, 142 respellings (including 15 shipped), 25 with at least one fallback piece.

## Notes for the pick

- Pieces with fallback notes are resolved by the app's own fallback, so the owner should prefer a candidate with no fallback note when the fallback sounds poor.
- This is a docs-only change: no code, lexicon, or test changes. The check and render scripts are private tooling, not in the repo.
