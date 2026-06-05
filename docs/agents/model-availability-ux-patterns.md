# Model Availability State — Canonical Reference

## 4-state machine

|State|Meaning|User action|
|---|---|---|
|Ready|Model is on disk and ready|None|
|Preparing|Download in progress or auto-queued|Cancel (user-initiated only)|
|Action Required|User must sign in, accept licence, etc.|Primary action button|
|Unavailable|Cannot be used (denied, unsupported, etc.)|Informational only|

## Truth table

See `DownloadState.kt` → `DownloadStateMapper.kt` for the full mapping.

## File locations

|File|Purpose|
|---|---|
|`core/model-availability/.../ModelAvailabilityState.kt`|Sealed class + subtypes|
|`core/model-availability/.../DownloadStateMapper.kt`|`DownloadState.toAvailability()`|
|`core/model-availability/.../StateBadge.kt`|Composable badge chip|
|`core/model-availability/.../ModelCard.kt`|ModelCard + ModelCardCompact|
|`core/model-availability/.../GatedModelStatus.kt`|Enum for gated model access|
|`core/model-availability/.../GatedModelStatusRepository.kt`|DataStore repository|

## Commit ordering (feature/model-availability-ux)

See `git log` — 16 commits, each atomic and CI-green.
