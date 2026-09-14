# ADR-003 — Code editor: Monaco in a WebView

* **Status:** Accepted (with a named exit route)
* **Date:** 2026-09-06
* **Affects:** `:feature:editor`, RISK-003, RISK-010

## Context

The IDE needs a real code editor: syntax highlighting for PHP/Blade/JS/TS/HTML/CSS/JSON/YAML/
Markdown/SQL/Bash/XML/Kotlin, folding, multi-cursor, search/replace, diagnostics, and later
LSP-backed completion. Writing that from scratch is a multi-year project and is explicitly
out of scope per the brief's "do not reinvent existing technologies" principle.

## Options considered

| Option | Verdict |
|---|---|
| **Monaco in WebView** | **Chosen.** MIT-licensed, the same engine as VS Code, complete language support, a real LSP-shaped diagnostics/completion API. |
| CodeMirror 6 in WebView | Strong runner-up. Markedly better touch/IME behaviour and a far smaller footprint, but a smaller language ecosystem and a different extension surface. **Kept as the named fallback.** |
| Sora Editor (native Android) | Genuinely good native editor with real mobile ergonomics. Rejected as primary because its language tooling would have to be built out separately, and it does not give a path to VS Code-shaped extensions. |
| Write our own | Rejected. Multi-year, no differentiating value. |

## Decision

Monaco, loaded from bundled local assets, behind an `EditorBridge` interface.

### Loading strategy

Content is served through `androidx.webkit.WebViewAssetLoader` at
`https://appassets.androidplatform.net/assets/`.

This matters and is not cosmetic. Loading Monaco from `file://` gives an opaque origin, which
breaks the same-origin policy, `XMLHttpRequest`, and web workers. `WebViewAssetLoader` provides
a real HTTPS origin backed by local assets, which is the approach Android's own documentation
recommends for exactly this reason, and it keeps the door open to Monaco's worker-based
tokenization and language services.

### Bridge design

```
Compose  ──state──▶  EditorViewModel  ──commands──▶  EditorBridge  ──JSON──▶  WebView  ──▶  Monaco
   ▲                                                      │
   └──────────────── events (dirty, cursor, save) ◀────────┘
```

**Kotlin owns the truth.** Document content, dirty state and the save lifecycle live in Kotlin.
The WebView is a *view*. If it crashes or is reclaimed, no user data is lost — the buffer is
reloaded into a fresh WebView.

**The bridge is deliberately narrow.** A single `@JavascriptInterface` object exposing one
`postMessage(String)` entry point, with a closed set of versioned message types. There is no
generic "call any Kotlin method" surface, no filesystem access from JavaScript, and no path to
secrets. Every inbound message is size-capped and schema-validated before parsing; anything
unknown or malformed is dropped and logged at `SECURITY` level rather than best-effort parsed.

WebView content is treated as untrusted, because project files rendered in it *are* attacker-
controlled input (RISK-010).

## Consequences

* Bundle size grows by the vendored Monaco `min/vs` payload. Accepted for offline-first;
  Monaco is fetched by `tools/fetch-monaco.sh` and is git-ignored rather than committed.
* Memory cost of a WebView plus Monaco on a 4 GB device is a real risk and is **not yet
  measured on hardware** (RISK-003). It must be measured before Phase 1 is declared complete.
* Monaco's touch and soft-keyboard handling on Android is the known weak point.

## What device testing changed (2026-09-06)

Running the app on an Android 11 emulator found two things no unit test could have.

### 1. Monaco needs a modern WebView, and WebView is not tied to Android version

The emulator carries the stock AOSP WebView — **Chromium 83**, from 2020. Monaco 0.52 fails to
parse there with `Uncaught SyntaxError: Unexpected token '{'` on a static initialisation block,
which needs Chromium 94+. The result was a blank pane and no explanation.

The load-bearing detail: **WebView ships as a separately-updatable app.** A current Android 15
device with updates disabled can carry an ancient engine; an Android 11 device with Play
Services is usually fine. Gating on `Build.VERSION.SDK_INT` would be wrong in both directions,
so `WebViewCompatibility` reads the actual WebView package version instead and, below the
threshold, renders a notice naming the version found, the version needed, and the fix.

This adds a real constraint to the ADR-003 decision that was not previously acknowledged:
**choosing Monaco means requiring a Chromium 94+ WebView.** That is acceptable for the Play
Store audience and is a genuine problem for AOSP and de-Googled devices — which strengthens the
CodeMirror 6 exit route below, since CodeMirror is both more mobile-friendly *and* less
demanding of the engine.

### 2. The CSP blocked Monaco's own bootstrap

`script-src 'self'` blocked the inline `<script>` that configured the AMD loader, so Monaco
silently never initialised — the only trace was a console violation.

Fixed by moving it to `bootstrap.js`, **not** by adding `'unsafe-inline'`. This page renders
untrusted project content (RISK-010); re-enabling inline script to save one file would have
traded a real security boundary for convenience.

The lesson worth keeping: a strict CSP fails *silently* in a WebView. There is no crash, no
error state, nothing on screen — which is precisely why the editor now needs an explicit
"engine cannot run" state rather than trusting that failures are visible.

## Exit route

If on-device measurement shows unacceptable IME/touch behaviour or memory use — or if the
Chromium 94+ requirement proves too restrictive — we swap to CodeMirror 6. The `EditorBridge`
interface exists so that this replaces one module's internals and touches no feature code. This
is a designed-in exit, not a hope.

**Still unmeasured:** Monaco has not yet actually rendered on any available device, so its
memory footprint and IME/touch behaviour remain unknown. That measurement needs a device with a
current WebView.

## References

* [Load in-app content — Android Developers](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
* [WebViewAssetLoader — Android reference](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
