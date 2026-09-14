# ADR-006 — Extension architecture and VS Code compatibility

* **Status:** Accepted (design only — Phase 8)
* **Date:** 2026-09-06
* **Affects:** Phase 8, RISK-005, legal posture

## Context

Users expect a modern IDE to be extensible, and the obvious question is "does it run VS Code
extensions?". The honest answer is *some of them, eventually, with caveats* — and the brief is
explicit that we must not promise full compatibility unless it has been implemented and tested.

The technical obstacle: the overwhelming majority of VS Code extensions assume a **Node.js
extension host** and use Node APIs (`fs`, `child_process`, `path`) directly. On Android that
requires the Phase 2 runtime, and even then a Node process per extension is a memory profile a
phone will not tolerate.

## Decision

### A first-class native extension model, plus honest tiering for `.vsix`

```
ExtensionManager
    ├── ExtensionRegistry     installed extensions and their state
    ├── ExtensionManifest     declared contributions and permissions
    ├── ExtensionHost         the isolate an extension runs in
    ├── ExtensionPermissions  deny-by-default capability grants
    └── ExtensionStorage      per-extension sandboxed storage
```

Extensions may contribute commands, editor features, themes, language definitions, snippets,
panels, project templates, AI tools and file viewers.

### Three tiers, labelled truthfully in the UI

| Tier | What | Support |
|---|---|---|
| **1 — Native** | Written for MobileForge; own manifest and permissions | **Supported** |
| **2 — Web-model VS Code** | Declares `extensionKind` including `web`, uses no Node API | **Best effort**, per-extension verdict |
| **3 — Node/desktop VS Code** | Requires the Node extension host | **Experimental at best**, gated behind Phase 2, never presented as supported |

### `.vsix` files are inspected, never trusted

The install pipeline is a sequence of checks, each producing a *specific* reason on failure:

1. Validate the archive (a `.vsix` is a zip; a malformed one is rejected outright)
2. Read `package.json` / `extension.vsixmanifest`
3. Check the declared `engines.vscode` range
4. Check `extensionKind` — is `web` declared?
5. Enumerate dependencies
6. Detect Node API usage
7. Produce a verdict
8. Install only if compatible
9. Otherwise **explain why**, naming the specific blocker

A generic "installation failed" is not an acceptable outcome. "This extension requires the Node
file-system API, which is not available in the web extension host" is.

### Extensions are untrusted code

Permissions are declared in the manifest, shown to the user before install, and enforced at
runtime — deny-by-default, same engine as agent permissions:

```
filesystem   terminal   network   workspace   ai   git   secrets
```

`secrets` is never grantable as a blanket permission.

## Consequences

* We ship a smaller, working extension story instead of a broken large one.
* Some popular extensions will never run. The UI says which, and why, rather than failing
  mysteriously.
* Tier 1 extensions can do things a VS Code extension cannot — contribute AI tools, mobile
  panels, project templates — which is where the differentiated value is.

## Legal position

Non-negotiable, and stated here so it is not rediscovered later:

* **Do not copy proprietary VS Code code.** Code-OSS is MIT; the Microsoft-branded VS Code build
  is not, and the Marketplace has its own terms.
* **Do not use Microsoft trademarks** or imply this product is VS Code.
* **Do not claim VS Code compatibility** in store listings or marketing.
* Monaco is MIT and is used under that licence, with attribution bundled.
* Every third-party component is licence-checked before integration (Android compatibility,
  maintenance status, redistribution terms).
