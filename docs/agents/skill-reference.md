# Skills

**Native (Kotlin/JVM):** flashlight, DND, Bluetooth, alarm/timer, email (`ACTION_SEND`), SMS (`SEND_SMS`), notes (Room), media (MediaSession via NotificationListenerService)

**Wasm (Chicory, pure JVM v1.0+):** sandboxed — no direct OS access. JSON via shared linear memory. 5s wall-clock timeout, 16MB memory cap, 1MB output limit. HTTP via domain-scoped bridge functions with URL allowlist — never a generic `fetch()`.

Contract-first: define `SkillSchema` JSON schema before logic. Version bump in manifest on every change. Schema injected via `SkillRegistry.buildFunctionDeclarationsJson()`.