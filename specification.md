***

# Technical Specification: Jandal AI — Local-First Android AI Assistant ("Project Gemma-Mobile")

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
| **Embedding Model** | EmbeddingGemma-300M | Gecko-110m-en |
| **Action Model** | FunctionGemma-270M | FunctionGemma-270M |
| **RAM Utilization** | ~3.5GB - 4.0GB | ~1.8GB - 2.2GB |

---

## 3. Memory Architecture: Local RAG
The assistant utilizes a hybrid memory system to maintain long-term facts and short-term context.



### 3.1 Short-Term (Session) Memory
* **Implementation:** Managed via the LiteRT-LM KV Cache.
* **Window:** 4,096 tokens (Performance) / 2,048 tokens (Compatibility).
* **Summarization:** When the window reaches 80% capacity, the system triggers the 2B/4B model to generate a recursive summary, which is then injected into the prompt to preserve context while freeing tokens.

### 3.2 Long-Term (Semantic) Memory
* **Database:** SQLite with the **VSS (Vector Similarity Search)** extension.
* **Vector Encoding:** Gecko-110m or EmbeddingGemma-300M processes chat logs and user-defined facts in the background.
* **Retrieval:** Every user query triggers a cosine-similarity search. The top 3–5 most relevant "memory fragments" are added to the system prompt as contextual truth.

---

## 4. Skill & Tool Framework
To ensure the assistant can perform tasks, it uses a decoupled skill registry.

### 4.1 Native Skills (Kotlin/JVM)
High-performance, high-privilege tasks integrated directly with the Android OS:
* **Android Actions:** Flashlight, DND, Bluetooth, Alarm/Timer management.
* **Communication:** Drafting emails (ACTION_SEND) and background SMS (SEND_SMS).
* **Local Productivity:** Integration with Google Keep via Intents for note-taking and shopping lists.
* **Media Control:** MediaSession API hooks for controlling local players like Plexamp.

### 4.2 Extensible Skills (WebAssembly)

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
1.  **Phase 1:** Core LiteRT-LM integration with GPU/NPU acceleration for Gemma-4. Backlog polish: active model/backend/tier display in Settings (#59); user-controlled E2B/E4B model selection persisted via DataStore (#60).
2.  **Phase 2:** Deployment of sqlite-vec and EmbeddingGemma-300M for local semantic search and RAG.
3.  **Phase 3:** FunctionGemma intent router + Native Skills + full Voice I/O. Includes **Live Mode** (#64) — real-time offline voice conversation via Silero VAD endpointing → Gemma-4 audio tensor input → Sherpa-ONNX/Piper TTS → barge-in, 28s rolling buffer, <1.5s time-to-first-audio. Includes **"Hey Jandal" wake word** (#65) — always-on local detection via openWakeWord (ONNX Runtime) + VoiceInteractionService with 3s ring buffer handoff to Live Mode pipeline.
4.  **Phase 4:** Integration of the Chicory Wasm Runtime and the GitHub-based Skill Store.
5.  **Phase 5:** Optimization for 8GB devices (dynamic loading/unloading of weights).
