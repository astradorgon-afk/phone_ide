# ADR-004 — AI provider and agent abstraction

* **Status:** Accepted (contracts in Phase 1, adapters in Phase 5)
* **Date:** 2026-09-06
* **Affects:** `:ai:api`, Phases 5 and 6

## Context

The product must support Anthropic, OpenAI, Gemini, OpenRouter, Ollama, arbitrary
OpenAI-compatible endpoints, and providers that do not exist yet — plus *agents* such as
OpenCode, Claude Code CLI and Codex CLI. The brief is explicit that Claude must not be
hard-coded and that the IDE must remain fully usable with no AI configured at all.

## Decision

### 1. Provider and agent are different concepts

* **`AiProvider`** supplies model access: authentication, endpoint, request/response shape,
  streaming, token accounting.
* **`AiAgent`** supplies behaviour: planning, tool selection, execution loop, checkpoints.

They compose freely. `OpenCode + Claude`, `built-in agent + Gemini`, and
`Claude Code CLI + its own auth` are all expressible without special-casing.

Conflating them is the mistake that makes an IDE impossible to re-point at a new vendor.

### 2. `:ai:api` is pure JVM

No provider SDK, no HTTP client, and no Android type may become a compile dependency of the
UI. Feature modules depend on `:ai:api` interfaces only. Concrete adapters live in
`:ai:providers:*` modules added in Phase 5 and are wired at the composition root.

### 3. Capabilities are declared and detected, never assumed

```
supportsStreaming   supportsTools      supportsVision
supportsReasoning   supportsJsonMode   supportsLongContext
```

The UI adapts to the capability set. A model without tool support does not get an agent mode
with a greyed-out button and a runtime failure — it does not get offered that mode.

### 4. BYOK, with keys the app can hold but not leak

Users supply their own credentials. Keys go to Android Keystore-backed storage, are write-only
from the UI's perspective (never displayed after entry), are excluded from logs, crash reports
and backups, are never placed in a prompt, and are never reachable from WebView JavaScript.

There is no MobileForge backend proxying model traffic. All provider traffic is HTTPS directly
from the device to the user's chosen endpoint — with the deliberate exception of Ollama, where
a user-specified local HTTP endpoint is permitted and clearly labelled as local-only.

### 5. Context is assembled, bounded and sanitised

`ContextManager` selects: current file, selection, related files, project structure,
diagnostics, recent errors, Git diff, terminal output, Laravel metadata — under an explicit
token budget. **The whole project is never sent.**

Everything sourced from the project or from tool output is inserted as explicitly-labelled
untrusted data and passes through secret redaction first (ADR-005, SECURITY.md).

### 6. The IDE must work with zero providers configured

No screen may hard-depend on an AI subsystem. If no provider is configured, AI surfaces are
absent or clearly inert — never a button that fails at runtime.

## Consequences

* One extra indirection per provider; worth it to keep vendor swaps a one-module change.
* Capability detection needs a per-provider model catalogue that will drift from reality and
  must be refreshable without an app update.
* Cost and token reporting can only use provider-reported values. **We never fabricate pricing;**
  if a provider does not report usage, the UI says so rather than estimating.
