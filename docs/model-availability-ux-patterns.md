# Model Availability UX Patterns

> **Purpose:** This is the canonical reference for user experience patterns around model discovery,
> acquisition, access, selection, and lifecycle management within Jandal AI.
> Before implementing any screen or flow related to models, read this document first.
>
> This document describes **how things should work**, not what is currently implemented.
> For feature status see [`ROADMAP.md`](./ROADMAP.md). For technical architecture see
> [`SPECIFICATION.md`](./SPECIFICATION.md).
>
> **Applies to:** Model Management, Voice Settings, Assistant Settings, Agent Configuration,
> and all future model-enabled features.

---

## Core UX Principle

**Users Want an Assistant, Not a Model Manager.**

Jandal AI should automatically perform any action that can be completed without user intervention.
Users should only be interrupted when human action is genuinely required.

### Automatic (no user action needed)

- Downloading required models
- Downloading model updates
- Validating downloads
- Repairing corrupted downloads
- Selecting default models during onboarding

### User Action Required

- Signing in to a provider
- Accepting a license agreement
- Requesting access to gated models
- Purchasing a model
- Resolving insufficient storage

---

## Design Principles

### Frictionless First Run

A new user should be able to install Jandal AI and begin interacting with the assistant without
manually downloading required models. Required models should automatically begin acquisition during
onboarding.

### Progressive Disclosure

Most users care about:

1. Is it available?
2. Can I use it?
3. What do I need to do next?

Technical details should be available but secondary.

### Action-Oriented UX

The primary action should always represent the next required step.

Examples: Sign In, Review License, Request Access, Retry, Select Model.

Never show multiple competing primary actions.

### Consistent State Language

The same labels should be used throughout the application. Top-level model states:

| State | Meaning |
|---|---|
| **Ready** | Model is fully available |
| **Preparing** | Jandal AI is performing background work |
| **Action Required** | User must complete an action |
| **Unavailable** | Model cannot currently be used |

Detailed lifecycle states map to top-level states as follows:

| Detailed state | Top-level state |
|---|---|
| Downloading, updating, validating, repairing | **Preparing** |
| Approval Pending (awaiting review) | **Preparing** |
| Authentication Required | **Action Required** |
| License Acceptance Required | **Action Required** |
| Access Approval Required | **Action Required** |
| Access Denied | **Unavailable** |
| Provider unavailable / model removed / unsupported device | **Unavailable** |
| Insufficient storage | **Action Required** |
| Tier-preferred optional model (auto-queued by hardware tier) | **Preparing** |
| Not installed (optional, not yet queued) | **Ready** |
| Ready to Download (optional, available for install) | **Ready** |

This mapping ensures every screen uses the same top-level badge regardless of the underlying cause.

Detailed lifecycle states appear only when additional information is needed.

---

## Model Sources

Jandal AI supports multiple model providers.

Examples: Hugging Face, Google AI Edge, Ollama Registry, Local Import, Custom URL.

Provider-specific requirements should integrate into the common Jandal AI access workflow. The user
experience should remain consistent regardless of provider.

---

## Required vs Optional Models

### Required Models

Required models are necessary for core application functionality.

Examples:

- Default assistant model
- Default speech-to-text model
- Default text-to-speech model

**Behaviour:**

- Automatically acquired
- Automatically updated
- Automatically validated
- Clearly identified as system-managed

Users should not need to manually download required models.

### Optional Models

Optional models provide additional functionality.

Examples:

- Alternative chat models
- Coding models
- Experimental models
- Large premium models

**Behaviour:**

- User chooses whether to install
- User controls storage usage
- User may remove at any time

---

## User-Facing Availability States

### Ready

**Description:** The model is fully available and can be used immediately.

**Examples:** Download complete, validation complete, access requirements satisfied.

### Preparing

**Description:** Jandal AI is performing background work to make the model available.

**Examples:** Downloading, updating, validating, repairing. The user should not need to take action.

**Example surface:**

```
Llama 4 Scout
Preparing
Downloading 62%
```

### Action Required

**Description:** Jandal AI cannot proceed until the user completes a required action.

**Examples:** Sign in to provider, accept license, request access, resolve storage issue.

The required action should be clearly explained with a single primary action button.

**Example surface:**

```
Voice Model
Action Required
Sign in to Hugging Face to continue.
[Sign In]
```

### Unavailable

**Description:** The model cannot currently be used.

**Examples:** Access denied, provider unavailable, model removed by publisher, unsupported device.

A clear explanation should always be provided.

---

## Access Workflow

Jandal AI should automatically progress through all stages that do not require user interaction.

### Automatic Workflow

```
Ready → Downloading → Validating → Ready
```

Users should not be required to manually trigger these steps.

### User Intervention Workflow

```
Sign In Required → License Acceptance Required → Access Approval Required
    → Ready → Downloading → Validating → Ready
```

Jandal AI should automatically continue once the user completes the required action.

### Optional Model Workflow

Optional models follow the same lifecycle, but the initial trigger differs:

```
Ready (Not installed) → Ready to Download → Downloading → Validating → Ready
```

For optional models, "Ready to Download" is a visual affordance (e.g. a "Download" button on the card) rather than a separate state badge. The badge remains **Ready**.

Tier-preferred optional models (e.g. E-4B on flagship devices) are auto-queued by the system and follow the automatic workflow above — they show **Preparing** without user action.

### Storage-Blocked Workflow

```
Preparing → Insufficient Storage (Action Required) → Retry → Downloading → Validating → Ready
```

When storage runs out during download or validation, the state transitions to **Action Required** with a "Free up space" action.

---

## Provider Requirements

### Authentication Required

**Description:** The provider requires user authentication.

**Examples:** Hugging Face gated repositories, commercial providers.

**Primary Action:** Sign In

### License Acceptance Required

**Description:** The user must review and accept licensing terms.

**Examples:** Llama Community License, Gemma License, OpenRAIL.

**Primary Action:** Review License
**Secondary Action:** Accept License

The license name should always be visible.

### Access Approval Required

**Description:** The provider requires approval before access is granted.

**Examples:** Gated Hugging Face models, research models, commercially restricted models.

**Primary Action:** Request Access
**Secondary Action:** Open Provider Page

### Approval Pending

**Description:** An access request has been submitted and is awaiting review.

**Primary Action:** Check Status

Jandal AI should periodically recheck approval status automatically.

### Access Denied

**Description:** The provider has rejected the access request.

**Primary Action:** View Details
**Secondary Action:** Request Again (when supported by the provider)

---

## Model Card Pattern

All models should use a consistent card design.

### Primary Information

- Model Name
- Publisher
- Capability
- Current State
- Requirement Level (Required / Optional) — shown as a chip or label
- Selection State (Selected / Not selected) — shown as a radio indicator or label

### Secondary Information

- Provider
- Size
- Version
- Storage Usage

Requirement Level and Selection State may also appear here if the card design prioritises them
over the primary section — but they must always be visible on the card.

### Advanced Information

Collapsed by default.

Examples: Quantisation, Context Length, Runtime, Last Validation Date, Technical Metadata.

### Primary Action

One action only — displayed on the card itself.

Examples: Sign In, Review License, Request Access, Select Model, Retry.

The primary action should always represent the next required step.

Secondary actions (Accept License, Open Provider Page, Request Again) are available *after* the primary action opens the relevant flow — not as a second button on the card. For example: the card shows "Review License"; inside the license review screen, the user can "Accept License".

---

## Model Selection

Model selection and model acquisition are separate concerns.

- **Acquisition** determines whether a model is available.
- **Selection** determines which available model is used.

These workflows should remain independent.

### Single Active Model

Most capabilities should have one active model.

Examples: Chat, Voice, Embeddings.

Selecting a model should never trigger licensing or authentication workflows. Those requirements
should already be resolved before selection becomes available.

### Missing Selected Models

If a selected model becomes unavailable:

- Preserve the selection reference
- Display unavailable status
- Prompt the user to restore or replace the model

Jandal AI should never silently switch models.

---

## Screen Responsibilities

### Voice Preferences

Voice Preferences is not a download manager.

**Should:**

- Display voice-capable models
- Display availability status
- Allow selection

**Should not** implement separate:

- Download workflows
- Authentication workflows
- Licensing workflows
- Access workflows

These are shared platform concerns handled by Model Management.

### Model Management

Model Management is the authoritative interface for model administration.

**Responsibilities:**

- Discover models
- Manage providers
- Manage authentication
- Review licenses
- Request gated access
- Manage storage
- Remove models
- Update models

---

## Acceptance Criteria

A user should always be able to answer:

1. Can I use this model right now?
2. If not, what is blocking me?
3. What do I need to do next?
4. Is Jandal AI already working on it?
5. Is this model required or optional?
6. Is this model selected?
7. Which provider supplies this model?
8. Are there licensing or access restrictions?

If these questions cannot be answered immediately from the UI, the design should be reconsidered.