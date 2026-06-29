# Repo Map

Load this when navigating unfamiliar modules.

## Key files by area

### Architecture and conventions
| Path | Purpose |
|------|---------|
| `.omp/AGENTS.md` | **Single source of truth** — architecture, conventions, agent workflow |
| `docs/SPEC_INDEX.md` | Spec authority, categories, and update rules |
| `docs/SPECIFICATION.md` | Detailed technical spec, module breakdown, API contracts |
| `docs/UX_PATTERNS.md` | Canonical UI and navigation patterns |
| `docs/ROADMAP.md` | Product roadmap and phase tracking |
| `CONTRIBUTING.md` | Contributor guidelines |

### Agent configuration
| Path | Purpose |
|------|---------|
| `.opencode/agents/coordinator.md` | Orchestrator agent |
| `.opencode/agents/android-developer.md` | Kotlin/Compose/Gradle implementor |
| `.opencode/agents/llm-engineer.md` | LiteRT/RAG/prompt specialist |
| `.opencode/agents/test-writer.md` | Unit + Compose test writer |
| `.opencode/agents/spec-writer.md` | Documentation specialist |
| `.opencode/agents/code-reviewer.md` | Security, memory safety, LiteRT anti-patterns reviewer |
| `.opencode/agents/wasm-skill-author.md` | Rust to Wasm skill author |

### Core modules
| Path | Purpose |
|------|---------|
| `app/` | Entry point, Hilt DI, navigation, splash |
| `core/inference/` | LiteRT-LM engine wrapper, model manager |
| `core/voice/` | STT, TTS, voice mode, push-to-talk |
| `core/memory/` | sqlite-vec JNI, EmbeddingGemma, RAG pipeline |
| `core/wasm/` | Chicory Wasm host, bridge functions |
| `core/ui/` | Shared Compose components, Material 3 |
| `core/skills/` | SkillInterface, SkillRegistry, schema generation |

### Feature modules
| Path | Purpose |
|------|---------|
| `feature/chat/` | Chat screen, conversation list, ChatViewModel |
| `feature/settings/` | Memory management, skill store, model info |
| `feature/onboarding/` | ~~First-launch model download~~ (dormant) |
| `feature/widget/` | Glance homescreen widget, VoiceCommandActivity |
| `feature/convert/` | Text conversion utilities |

### Documentation
| Path | Purpose |
|------|---------|
| `docs/SPEC_INDEX.md` | First stop for spec authority and update rules |
| `docs/specs/` | Subsystem behaviour specs, such as permissions UX |
| `docs/` | Specs, testing docs, planning, and operational guides |
| `docs/research/` | Technical research and draft design documents |
| `docs/testing/` | Test specifications, evidence schemas, and outcomes |
| `memory://root/` | Project memory (MEMORY.md, memory_summary.md) |

### Scripts and config
| Path | Purpose |
|------|---------|
| `gradle/libs.versions.toml` | Version catalog |
| `settings.gradle.kts` | Module configuration |
| `scripts/download-models.sh` | Download model weights from HuggingFace |
