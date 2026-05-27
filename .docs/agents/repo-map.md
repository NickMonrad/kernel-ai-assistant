# Repo Map

Load this when navigating unfamiliar modules.

## Key files by area

### Architecture & conventions
| Path | Purpose |
|------|---------|
| `.omp/AGENTS.md` | **Single source of truth** — architecture, conventions, agent workflow |
| `docs/SPECIFICATION.md` | Detailed technical spec, module breakdown, API contracts |
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
| `.opencode/agents/code-reviewer.md` | Security/memory/perf reviewer |
| `.opencode/agents/wasm-skill-author.md` | Rust→Wasm skill author |

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
| `docs/` | Research specs, testing docs, planning |
| `docs/research/` | Technical research documents |
| `docs/testing/` | Test specifications and outcomes |
| `memory://root/` | Project memory (MEMORY.md, memory_summary.md) |

### Scripts & config
| Path | Purpose |
|------|---------|
| `gradle/libs.versions.toml` | Version catalog |
| `settings.gradle.kts` | Module configuration |
| `scripts/download-models.sh` | Download model weights from HuggingFace |
