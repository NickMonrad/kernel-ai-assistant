# Plan: #645 Verbose RAG Candidate Logging

## Changes

### 1. `RagRepository.kt` — Add verbose logging constant and helper

Add `VERBOSE_TAG = "RagRepository-V"` and a `logVerbose()` helper that checks `Log.isLoggable(VERBOSE_TAG, Log.VERBOSE)`.

### 2. `RagRepository.kt` — Log full candidate lists after sorting (before budget loop)

After lines 128-134 (after `coreResults` and `distilledResults` are sorted), log:
- Core candidates: rank, term/snippet, source, score, dist, lastAccessedAt
- Distilled candidates: rank, snippet, source, score, dist, conversationId

### 3. `RagRepository.kt` — Log per-entry budget decisions in core loop

Inside the core memory budget loop (lines 140-151), log each entry as IN or OUT (budget).

### 4. `RagRepository.kt` — Log per-entry budget decisions in distilled loop

Inside the distilled/episodic budget loop (lines 160-167), log each entry as IN or OUT (budget).

## Implementation approach

Use `rtk` to apply edits in `/tmp/kai-645/`.
