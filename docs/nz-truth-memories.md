# NZ Truth Memories — Authoring Guide

Jandal's NZ cultural knowledge lives in a structured JSON corpus that is embedded and stored as `agent_identity` memories at app launch. This guide explains how to add, edit, or remove entries and ensure they are reseeded on device.

---

## The Two Files You Always Touch

### 1. The corpus
```
core/inference/src/main/assets/nz_truth_memories.json
```
Contains all 135 (and growing) NZ truth entries. Edit this to add, change, or remove entries.

### 2. The seed guard key
```
core/inference/src/main/java/com/kernel/ai/core/inference/JandalPersona.kt
```
Line near the top:
```kotlin
private const val KEY_TRUTHS_SEEDED = "truths_seeded_v3"
```
**Bump the version number every time you change the corpus.** On next app launch, the app detects the new key, wipes all existing `agent_identity` memories, and reseeds from scratch.

---

## Entry Schema

```json
{
  "id": "nz_010",
  "term": "Monique says you're dumb",
  "category": "meme",
  "definition": "What you say when someone does something dumb. Straight from the iconic 2011 NZTA Ghost Chips ad.",
  "trigger_context": "When the user mentions Monique, or does something dumb/silly.",
  "vibe_level": 3,
  "vector_text": "Monique says you're dumb. NZTA Ghost Chips ad 2011. Calling someone out for being silly. Iconic Kiwi phrase.",
  "metadata": {
    "tags": ["ghost-chips", "nzta", "banter", "catchphrase"],
    "era": "2011"
  }
}
```

### Field reference

| Field | Purpose | Tips |
|-------|---------|------|
| `id` | Unique identifier (`nz_001`–`nz_NNN`) | Never reuse a retired ID |
| `term` | Label used in logs and RAG injection prefix `[NZ Context: <term>]` | Keep short and memorable |
| `category` | Organisational grouping | See categories below |
| `definition` | **Injected into the prompt** — this is what Jandal actually says | Write as a confident statement, not a dictionary entry |
| `trigger_context` | Human-readable note on when this should fire | Not used in code — just for authors |
| `vibe_level` | Controls RAG distance threshold (see below) | 1–5 |
| `vector_text` | **What gets embedded** — dense keywords for vector similarity | Use keywords, not prose; include synonyms and related terms |
| `metadata.tags` | Searchable tags | Optional but useful |
| `metadata.era` | Year/decade the reference is from | Optional |

---

## Vibe Levels

The vibe level controls how loosely the RAG will match this entry. Lower = stricter match required; higher = fires more easily.

| Vibe | Distance threshold | Use for |
|------|--------------------|---------|
| 1–2 | ≤ 1.10 | Broad cultural facts, and any entry whose trigger involves **indirect phrasing** (the user won't say the term itself) |
| 3 | ≤ 0.95 | Entries where the user is expected to **explicitly mention** the topic or term in their query |
| 4–5 | ≤ 0.80 | Slang/meme entries that fire only when the user **actually uses the word** (e.g. "that's munted") |

### Choosing the right vibe level

**Use vibe 1–2 when:**
- The entry is a broad cultural fact (e.g. "NZ invented the pavlova")
- The trigger_context describes a scenario where the user's query won't contain the term (e.g. "Bugger" fires on "something went wrong", "Flash as" fires on "I got a new phone")
- The entry is important enough that missing it would cause hallucination (disputed facts, origin stories)

**Use vibe 3 when:**
- The user is expected to explicitly say the topic name in their query (e.g. "what is the Haka", "who is David Lange")
- A moderate match is appropriate — not a hair-trigger, but not obscure

**Use vibe 4–5 when:**
- The entry is for **interpreting Kiwi slang** the user types (e.g. "munted", "clanker", "nek minnit")
- You only want injection when the query is a near-direct match to the term
- The entry is a very niche culture reference that shouldn't fire speculatively

> **Key rule:** If the trigger_context describes an indirect scenario ("when the user is rushing", "when something goes wrong"), the user's query won't contain the term — use vibe 1–2 and enrich `vector_text` with the indirect scenario phrases.

> **Debugging:** If an entry isn't firing when you expect it to, check logcat for `Core vec search: 15 raw results, distances=[...]`. If your entry appears at distance > threshold, lower the vibe level. If it doesn't appear at all (not in top 15), enrich the `vector_text` with more query-realistic phrases.

---

## Categories

| Category | Examples |
|----------|---------|
| `meme` | Ghost Chips, Monique says you're dumb, Beached as |
| `food` | Pavlova, Pineapple Lumps, Marmite, L&P |
| `slang` | Chur, Munted, Dag, Choice, Gutted, Stoked, Bro/Cuz |
| `attitude` | She'll be right, No worries, Good as gold |
| `daily_life` | Dairy, Togs, Bach/Crib, Smoko, Tiki Tour, Arvo |
| `maori` | Kia ora, Ka pai, Haere rā, Mahi, Whānau, Aroha |
| `sport` | All Blacks, Black Caps, America's Cup |
| `geography` | Northland, Fiordland, Rangitoto, Wop-wops |
| `history` | Treaty of Waitangi, Suffrage, Gallipoli |
| `culture` | Haka, Pōwhiri, Matariki, Scarfie, Bach/Crib |
| `politics` | MMP, Beehive, Jacinda |
| `music` | Six60, Crowded House, Lorde, Flight of the Conchords |
| `nature` | Kiwi bird, Tuatara, Kauri |

---

## Step-by-Step: Adding a New Entry

1. **Open the corpus:**
   `core/inference/src/main/assets/nz_truth_memories.json`

2. **Add your entry** at the end of the array. Use the next available ID (`nz_137`, `nz_138`, etc.):
   ```json
   {
     "id": "nz_137",
     "term": "Your term here",
     "category": "slang",
     "definition": "Confident one-liner Jandal will say.",
     "trigger_context": "When the user talks about X.",
     "vibe_level": 3,
     "vector_text": "keyword1. keyword2. related phrase. synonym.",
     "metadata": {
       "tags": ["tag1", "tag2"]
     }
   }
   ```

3. **Check the vocab file** — if the new term is something Jandal should use in conversation, add it to `jandal_vocab.json` too. See [Keeping Vocab and Corpus in Sync](#keeping-vocab-and-corpus-in-sync).

4. **Bump the seed guard** in `JandalPersona.kt`:
   ```kotlin
   // Before:
   private const val KEY_TRUTHS_SEEDED = "truths_seeded_v11"
   // After:
   private const val KEY_TRUTHS_SEEDED = "truths_seeded_v12"
   ```
   Add a comment explaining what changed.

5. **Build and install** the app. On first launch after update, logcat will show:
   ```
   JandalPersona: Loaded 136 NZ truth entries
   ChatViewModel: Seeding NZ truth memories...
   ChatViewModel: Seeded 136 NZ truth memories
   ```

5. **Test** by asking Jandal something related to your new entry. Check logcat for:
   ```
   RagRepository: NZ truth injected: [Your term] vibe=agent_identity dist=~0.XXX
   ```

---

## Step-by-Step: Editing an Existing Entry

1. Find the entry by `id` or `term` in `nz_truth_memories.json`
2. Edit the fields you want to change
3. Bump the seed guard key (same as adding — any corpus change needs a bump)
4. Rebuild and test

---

## Keeping Vocab and Corpus in Sync

Jandal has two related but separate NZ lingo files:

| File | Purpose |
|------|---------|
| `core/inference/src/main/assets/jandal_vocab.json` | Kiwi phrases **injected into the system prompt** each session — tells the LLM which expressions it should use in conversation |
| `core/inference/src/main/assets/nz_truth_memories.json` | NZ truth corpus **embedded as RAG memories** — grounding knowledge for when those expressions come up in conversation |

### The rule: if Jandal says it, RAG must know it

If a phrase is in `jandal_vocab.json`, Jandal will use it in responses. If a user then asks "what does that mean?" or the phrase appears in a query, the RAG system needs a corpus entry to ground Jandal's answer — otherwise it will hallucinate.

**Every term in `jandal_vocab.json` should have a matching entry in `nz_truth_memories.json`.**

### Adding a new vocab term

When adding a phrase to `jandal_vocab.json`:

```json
{
  "phrase": "sweet as",
  "meaning": "excellent, sounds great"
}
```

Also add a corpus entry:

```json
{
  "id": "nz_XXX",
  "term": "Sweet as",
  "category": "slang",
  "definition": "Multi-purpose approval phrase — excellent, sounds great, no problem...",
  "trigger_context": "When agreeing or expressing approval.",
  "vibe_level": 2,
  "vector_text": "Sweet as. Sweet as bro. Sounds great. Excellent. Approval. Agreement."
}
```

### Auditing the sync

To check which vocab terms lack a corpus entry, run:

```bash
python3 -c "
import json
with open('core/inference/src/main/assets/nz_truth_memories.json') as f:
    corpus = {e['term'].lower() for e in json.load(f)}
with open('core/inference/src/main/assets/jandal_vocab.json') as f:
    vocab = json.load(f)
missing = [v['phrase'] for v in vocab if v['phrase'].lower() not in corpus
           and not any(v['phrase'].lower() in t for t in corpus)]
print('Vocab terms missing from corpus:', missing or 'all good!')
"
```

---

## Writing Good `vector_text`

The `vector_text` is what the MiniLM model embeds. A better embedding = better RAG retrieval.

**Do:**
- Use short phrases and keywords separated by periods
- Include common ways the topic might be mentioned ("pavlova", "pav", "meringue dessert")
- Include related concepts ("Ghost Chips", "NZTA ad", "drink driving", "looking out for mates")
- Include the term itself — **including phonetic or alternate spellings** (e.g. "Good aftabull constanoon. Good afternoon constable.")
- For indirect-trigger entries, **include the scenario phrases** from `trigger_context` (e.g. Bugger: add "something went wrong, error, setback, oops")

**Don't:**
- Write prose sentences — they dilute keyword density
- Copy the `definition` verbatim — it's optimised for human reading, not embedding
- Make it too long — aim for 1–3 lines

**Example (good):**
```
"vector_text": "Ghost chips. Ghost George. You know I can't grab your ghost chips. NZTA 2011 ad. Drink driving. Looking out for mates."
```

**Example (bad):**
```
"vector_text": "Ghost Chips is a reference to the famous New Zealand Transport Agency advertisement from 2011 which featured a ghost named George offering chips to his friend."
```

---

## Writing Good `definition`

The `definition` is injected directly into Jandal's prompt as:
```
[NZ Context: Ghost Chips] From the iconic 2011 NZTA drink-driving ad. Ghost George offers chips; the protagonist says 'you know I can't grab your ghost chips, bro'. Means looking out for your mates.
```

**Do:**
- Write it as a confident, opinionated statement
- Include the key fact Jandal should assert
- Keep it under ~100 words (the field is truncated at 400 chars)

**Don't:**
- Hedge ("some people say...", "it is believed...")
- Use encyclopaedia-style dry prose
- Include claims you're not sure about

---

## Seed Guard Version History

| Key | When bumped |
|-----|-------------|
| `truths_seeded` | Initial flat-string corpus (8 entries) |
| `truths_seeded_v2` | Structured JSON corpus (92 entries) |
| `truths_seeded_v3` | Corrected Ghost Chips (nz_005) and Monique (nz_010) entries |
| `truths_seeded_v4` | Pavlova hallucination fix — added Anna Pavlova 1926 NZ tour detail |
| `truths_seeded_v5` | Enriched thin definitions (No. 8 Wire, Skux as, Munted, Pineapple Lumps) + hallucination guards in system prompt |
| `truths_seeded_v6` | Pavlova vibe_level 3→2 — fix threshold miss on "who invented pavlova" queries (L2 0.986 > 0.95 cutoff) |
| `truths_seeded_v7` | 5 entries vibe 3→2 for indirect triggers: Bugger, Haka, Tall Poppy, Flash as, Always blow on the pie; richer vector_text |
| `truths_seeded_v8` | 7 trigger/vector_text mismatches fixed; Good Aftabull phonetic form added to vector_text |
| `truths_seeded_v9` | Added Carked it (nz_093), Flight of the Conchords + Binary Solo (nz_094), Hungus (nz_095) |
| `truths_seeded_v10` | Removed nz_003 (The 6-7 — not a real named NZ term); fixed nz_007 Munted (added drunk/intoxicated meaning); corpus 95 → 94 |
| `truths_seeded_v11` | 41 new entries (nz_096–nz_136) from three-source slang audit + jandal_vocab.json cross-check. Every term Jandal uses in conversation now has a RAG corpus entry. New categories: `attitude`, `daily_life`, `maori`. Corpus 94 → 135 |
