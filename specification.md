> **This document has moved.**  
> The authoritative technical specification is now at **[docs/SPECIFICATION.md](docs/SPECIFICATION.md)**.



## 1. Executive Summary
This document outlines the architecture for a privacy-centric, on-device mobile assistant. By leveraging the Google AI Edge (LiteRT) ecosystem and the Gemma-4 family of models, the assistant provides reasoning, memory retrieval, and device actions without relying on cloud APIs. The system uses a tiered hardware approach to ensure performance across a wide range of Android devices while maintaining a modular "Skill" framework for extensibility.

---

## 2. System Architecture
The assistant is built on a "Brain-Memory-Action" triad, managed by a central Orchestrator in Kotlin.

### 2.1 The LiteRT Inference Engine

* **Runtime:** Google AI Edge (LiteRT) with GPU and NPU delegates (AICore).
* **Quantization:** All models utilize 4-bit (INT4) weight quantization to minimize RAM footprint and maximize inference speed.
* **Orchestration:** A dynamic Model Manager handles "warm-up" and "teardown" of weights to manage peak RAM usage.

### 2.2 Hardware Tiering Strategy
| Component | Performance Tier (12GB+ RAM) | Compatibility Tier (8GB RAM) |
| :--- | :--- | :--- |
| **Reasoning Model** | Gemma-4 E-4B (4.0B params) | Gemma-4 E-2B (2.0B params) |
| **Embedding Model** | EmbeddingGemma-300M | EmbeddingGemma-300M |
| **Intent Routing (simple)** | `QuickIntentRouter` (Kotlin regex, zero RAM) | `QuickIntentRouter` (Kotlin regex, zero RAM) |
| **Intent Routing (complex)** | Gemma-4 E-4B native tool calling | Gemma-4 E-2B native tool calling |
| **RAM Utilization** | ~3.4GB (E4B GPU) | ~1.5GB (E2B GPU) |

> **Note (Apr 2026):** FunctionGemma-270M has been deprecated from the startup sequence. Its 289MB footprint causes lmkd to terminate the process during Gemma-4's GPU kernel compilation peak (~4–5GB transient). Simple device actions (torch, timer, alarm, DND, bluetooth, wifi) are now handled by a zero-overhead Kotlin `QuickIntentRouter`; complex tool calls (weather, calendar, email) are handled by the resident Gemma-4 model via its native `{"name": ..., "arguments": {...}}` JSON tool-call format. See issues #218, #219, #220.

---

## 3. Memory Architecture: Local RAG
The assistant utilizes a hybrid memory system to maintain long-term facts and short-term context.



### 3.1 Short-Term (Session) Memory
* **Implementation:** Managed via the LiteRT-LM KV Cache.
* **Window:** 4,000 tokens (Performance) / 2,000 tokens (Compatibility). **Note:** Exact powers of 2 (4096, 8192) are avoided — a `safeTokenCount()` guard nudges values down by ~2.4% to prevent a LiteRT GPU `reshape::Eval` buffer-alignment bug on Adreno GPUs.
* **Summarization:** When the window reaches ~75% capacity, the system triggers the resident model to generate a recursive summary, which is then injected into the prompt to preserve context while freeing tokens.

### 3.2 Long-Term (Semantic) Memory
* **Database:** SQLite with the **VSS (Vector Similarity Search)** extension.
* **Vector Encoding:** Gecko-110m or EmbeddingGemma-300M processes chat logs and user-defined facts in the background.
* **Retrieval:** Every user query triggers a cosine-similarity search. The top 3–5 most relevant "memory fragments" are added to the system prompt as contextual truth.

---

## 4. Skill & Tool Framework
To ensure the assistant can perform tasks, it uses a decoupled skill registry.

### 4.1 Native Skills — Tier 2: Quick Intent Router
Zero-overhead deterministic matching for simple device actions. A pure-Kotlin `QuickIntentRouter` uses regex/keyword patterns to match user input without loading any ML model. Matched actions execute directly via OS APIs. Latency: <5ms from text to action.

**Supported actions:**
* Flashlight on/off (`CameraManager.setTorchMode`)
* Timer (`AlarmClock.ACTION_SET_TIMER`)
* Alarm (`AlarmClock.ACTION_SET_ALARM`)
* Do Not Disturb toggle (`NotificationManager.setInterruptionFilter`)
* Bluetooth / Wi-Fi toggle
* Get current time / date
* Battery level (`BatteryManager`)

If no pattern matches, the query falls through to Tier 3 (Gemma-4 reasoning).

### 4.2 Native Skills — Tier 3: E4B Tool Calling
The resident Gemma-4 model handles complex tool calls requiring NLU and reasoning. When the model outputs a JSON function-call block (`{"name": "skill_name", "arguments": {...}}`), `SkillExecutor` parses it and dispatches to the registered `Skill` implementation. This enables multi-step reasoning before action execution.

**Supported skills (via `SkillRegistry`):**
* `get_weather` — geolocation + weather API
* `get_system_info` — battery, connectivity, device stats
* `save_memory` — persist notes/facts to Room
* `set_timer`, `run_intent` — OS action delegation

### 4.3 Extensible Skills (WebAssembly)

For community-driven and logic-heavy extensions, the system supports a **Wasm (WebAssembly)** runtime (via Extism or Wasmtime JNI).
* **Role:** Specialized logic like recipe parsing (e.g., RecipeTin Eats), data transformation, or web scrapers.
* **Sandboxing:** Wasm modules run in a restricted "padded cell" with no direct OS access.
* **Communication:** Data is passed between the Kotlin host and Wasm guest via JSON or Protobuf.

---

## 5. Security & Trust Model
* **Sandbox Isolation:** Wasm skills cannot access hardware or personal data unless the Kotlin host provides an explicit "bridge" function.
* **Permission Auditing:** Before installing a sideloaded skill, the app audits the Wasm "Import" section and warns the user of requested capabilities.
* **Resource Management:** A "Fuel" system limits the number of CPU cycles a Wasm plugin can consume, preventing battery drain or infinite loops.
* **User Agency:** Sideloaded skills (ZIP/URL) require a mandatory "Accept Risk" acknowledgement from the user.

---

## 6. Distribution & Lifecycle
* **Primary Source:** A curated GitHub repository containing a `manifest.json` with verified skill hashes and Wasm binaries.
* **Sideloading:** Support for local storage (ZIP) and direct URL ingestion for developer and power-user extensibility.
* **Hot-Swapping:** Wasm modules can be updated or replaced at runtime without requiring a full application restart or APK update.

---

## 7. Technical Pre-requisites for Development
* **Machine:** 32GB RAM minimum (to handle LiteRT builds and Android Emulators simultaneously).
* **Android Studio:** Ladybug (2024.2.1) or newer.
* **Android NDK:** Required for building SQLite-VSS and Wasm runtime JNI bridges.
* **Testing Hardware:** Physical device with 8GB–12GB RAM and an NPU/GPU-capable SoC. Validated on Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2 / NPU) and Google Pixel 10 (Tensor G5 / GPU). Qualcomm devices use the Hexagon NPU delegate; non-Qualcomm flagship devices (e.g. Pixel 10) use `Backend.GPU` automatically.

---

## 8. Implementation Roadmap
1. **Phase 1 ✅:** Core LiteRT-LM integration with GPU/NPU acceleration for Gemma-4. E4B loads on GPU (TTFT ~2.3s). GPU kernel cache, foreground service OOM protection, safe token-count alignment guard.
2. **Phase 2 ✅:** sqlite-vec + EmbeddingGemma-300M for local semantic search and RAG. Episodic distillation, context window management.
3. **Phase 3 (In Progress):** Resident Agent Architecture — deprecate FunctionGemma, build `QuickIntentRouter` for simple actions, enable Gemma-4 native tool calling for complex queries. Voice I/O (push-to-talk). Native skills: Flashlight, DND, Bluetooth, Alarm/Timer, Weather, Notes.
4. **Phase 4:** Chicory Wasm Runtime + GitHub-based Skill Store. Community-extensible sandboxed skills.
5. **Phase 5:** 8GB device optimisation (dynamic loading/unloading of weights, E2B fallback). **"Hey Jandal" wake word** (#65) — always-on local detection → `QuickIntentRouter` → E4B for complex follow-up.
