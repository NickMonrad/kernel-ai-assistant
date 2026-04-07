**Kernel AI Assistant** is a high-performance, local-first intelligent agent designed to bridge the gap between cloud-scale reasoning and total data sovereignty. Built specifically for the Android ecosystem, Kernel AI bypasses the cloud entirely, executing state-of-the-art **Gemma-4 E-4B and E-2B** models directly on your device's NPU and GPU.

At its core, the app operates on a **"Brain-Memory-Action"** triad:
* **The Brain:** Utilizes a tiered model-cascading architecture that adapts to your hardware, delivering "Gemini-class" reasoning on flagship devices while remaining fluid on 8GB RAM mid-range hardware.
* **The Memory:** Implements a local-first **RAG (Retrieval-Augmented Generation)** system using an **SQLite-VSS** vector database and **Gecko** embeddings, allowing the assistant to "remember" personal facts, family preferences, and complex history with zero data leakage.
* **The Action:** Features a modular skill framework. High-privilege "Hard Skills" are handled natively via **Kotlin/JVM** for deep OS integration (SMS, Device Toggles, Google Keep), while "Soft Skills" are executed in a secure **WebAssembly (Wasm)** sandbox for safe, community-driven extensibility.

Whether it’s orchestrating a week of meal plans from **RecipeTin Eats** or managing your smart home via **Home Assistant** hooks, **Kernel AI** provides a low-latency, private, and deeply integrated "OS for your life"—all while keeping your data exactly where it belongs: on your device.

---

### Key Technical Pillars
* **Inference:** Google AI Edge (LiteRT) with 4-bit quantization.
* **Extensibility:** Hybrid GitHub-indexed Skill Store (Wasm + Native).
* **Context:** Recursive 128k context window management with semantic summarization.
* **Privacy:** 100% offline-capable; no telemetry or external LLM API dependencies.
