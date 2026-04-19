# NZ Truth Memories — Authoring Guide

Jandal's NZ cultural knowledge lives in a structured JSON corpus that is embedded and stored as `agent_identity` memories at app launch. This guide explains how to add, edit, or remove entries and ensure they are reseeded on device.

---

## The Two Files You Always Touch

### 1. The corpus
```
core/inference/src/main/assets/nz_truth_memories.json
```
Contains all 92 (and growing) NZ truth entries. Edit this to add, change, or remove entries.

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
| 1–2 | ≤ 1.10 | Broad cultural facts that should fire on many queries (e.g. "New Zealand is in the Pacific") |
| 3 | ≤ 0.95 | Mid-range references — needs a reasonably close query match (most entries) |
| 4–5 | ≤ 0.80 | Niche or specific references — only fires on very close queries |

> **Tip:** If an entry isn't firing when you expect it to, try lowering the vibe level (e.g. 3 → 2) or enriching `vector_text` with more synonyms.

---

## Categories

| Category | Examples |
|----------|---------|
| `meme` | Ghost Chips, Monique says you're dumb |
| `food` | Pavlova, Pineapple Lumps, Marmite, L&P |
| `slang` | Chur, Sweet as, Munted |
| `sport` | All Blacks, Black Caps, America's Cup |
| `geography` | Northland, Fiordland, Rangitoto |
| `history` | Treaty of Waitangi, Suffrage, Gallipoli |
| `culture` | Haka, Pōwhiri, Matariki |
| `politics` | MMP, Beehive, Jacinda |
| `music` | Six60, Crowded House, Lorde |
| `nature` | Kiwi bird, Tuatara, Kauri |

---

## Step-by-Step: Adding a New Entry

1. **Open the corpus:**
   `core/inference/src/main/assets/nz_truth_memories.json`

2. **Add your entry** at the end of the array. Use the next available ID (`nz_093`, `nz_094`, etc.):
   ```json
   {
     "id": "nz_093",
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

3. **Bump the seed guard** in `JandalPersona.kt`:
   ```kotlin
   // Before:
   private const val KEY_TRUTHS_SEEDED = "truths_seeded_v3"
   // After:
   private const val KEY_TRUTHS_SEEDED = "truths_seeded_v4"
   ```
   Add a comment explaining what changed.

4. **Build and install** the app. On first launch after update, logcat will show:
   ```
   JandalPersona: Loaded 93 NZ truth entries
   ChatViewModel: Seeding NZ truth memories...
   ChatViewModel: Seeded 93 NZ truth memories
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

## Writing Good `vector_text`

The `vector_text` is what the MiniLM model embeds. A better embedding = better RAG retrieval.

**Do:**
- Use short phrases and keywords separated by periods
- Include common ways the topic might be mentioned ("pavlova", "pav", "meringue dessert")
- Include related concepts ("Ghost Chips", "NZTA ad", "drink driving", "looking out for mates")
- Include the term itself

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
