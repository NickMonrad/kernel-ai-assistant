# save_memory routing

Do not add patterns without checking.

| Input | Tier | Reason |
|-------|------|--------|
| `save/store/keep [to/in memory [that] \| that] <content>` | **Tier 2** | Direct intercept |
| `save/store/keep this/it …` | **Tier 3** | Anaphoric — needs LLM context |
| `remember [that] <content>` — not starting with this/that/it | **Tier 2** | Direct intercept; first-person normalised by `normaliseSaveContent()` |
| `remember that this/that/it …` | **Tier 3** | True anaphoric — needs LLM context |

`normaliseSaveContent()` handles full first-person conjugation: `I'm`/`I am` → `Name is`, `I have` → `Name has`, `I prefer/like/…` → conjugated third-person, bare `I` → `Name` (catch-all), `my` → `Name's`. Applied on both Tier 2 and Tier 3 code paths.