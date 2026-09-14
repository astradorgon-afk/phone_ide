# ADR-005 — Agent permission and autonomy model

* **Status:** Accepted (contracts in Phase 1, enforced in Phase 5)
* **Date:** 2026-09-06
* **Affects:** `:ai:api`, `:core:security`, RISK-011, RISK-012, RISK-015

## Context

An AI agent in an IDE can read source, write files, run shell commands, install packages,
commit and push. On a developer's own phone, with their own credentials, an over-permissive
agent is a credential-theft and data-loss vector — especially because the agent reads
attacker-controlled text (project files) as part of normal operation.

## Decision

### 1. Deny by default

An agent starts with **no** capabilities. Every tool declares the permissions it requires, and
the permission engine checks the *user's* grants — never anything the model produced or read.

### 2. Permission is per-capability, not per-agent

```
File read            Allowed / Ask / Blocked
File write           Allowed / Ask / Blocked
File delete          Allowed / Ask / Blocked
Terminal command     Allowed / Ask / Blocked
Package install      Allowed / Ask / Blocked   ← never covered by "terminal"
Git commit           Allowed / Ask / Blocked
Git push             Allowed / Ask / Blocked
Network request      Allowed / Ask / Blocked   ← separate from model access
Secret access        Ask only                  ← never grantable as "always"
```

**Package installation is deliberately not a subset of "terminal".** `composer install` and
`npm install` execute arbitrary third-party lifecycle scripts; a user granting "run commands"
has not consented to that (RISK-015).

**Network access is separate from model access.** Letting an agent talk to a model is not
letting it talk to the internet — that distinction is the whole defence against
"POST the .env to attacker.example".

### 3. Autonomy modes are a ceiling, not a bypass

| Mode | Agent may |
|---|---|
| **Manual** | Answer questions only. No tools. |
| **Assisted** | Read context and propose diffs. No writes. |
| **Agent** | Apply changes after per-operation permission. |
| **Autonomous** | Act within a pre-approved set, inside project boundaries, under hard limits. |

Autonomy raises the ceiling; it never overrides a `Blocked` grant, and it never removes
confirmation for destructive or network operations. **There is no mode that grants
system-wide access.**

### 4. Workspace trust bounds everything

The effective permission set is `min(user grant, trust tier ceiling)`.

| | Untrusted | Trusted |
|---|---|---|
| Read files | Limited | Allowed |
| Write files | Ask | Allowed |
| Terminal | **Blocked** | Ask |
| Network | **Blocked** | Ask |
| Git push | **Blocked** | Ask |

A freshly cloned repository is untrusted. Trust is asked before the project opens, not before
its first command.

### 5. Destructive operations are classified, not pattern-matched loosely

A command classifier flags a known-dangerous set — `rm -rf`, `git reset --hard`, `git clean`,
`git push --force`, `migrate:fresh`, `db:wipe`, `DROP`/`TRUNCATE` — and escalates to a
high-risk confirmation showing the exact command and its concrete consequence.

The classifier is **advisory-positive, never advisory-negative**: failing to match does not
downgrade an operation below its declared permission class. An unrecognised command in an
untrusted project is still blocked.

### 6. Session limits are hard stops

`maxRuntime`, `maxToolCalls`, `maxFilesChanged`, `maxCommands`. On breach the session pauses
with a stated reason and the user resumes deliberately.

### 7. Everything is audited and reversible

Every tool execution records tool, arguments, permission decision, timestamp and outcome.
Checkpoints are taken before batches of changes so a run can be inspected, accepted or reverted
as a unit, with Git used as the mechanism where a repository exists.

## Consequences

* More prompts than a "just let it run" agent. That is the intended trade.
* Requires a diff UI before Phase 5 can ship — AI changes are never applied invisibly.
* The permission engine is pure Kotlin in `:core:security`, unit-tested with adversarial cases.
