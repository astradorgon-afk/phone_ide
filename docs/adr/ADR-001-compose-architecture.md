# ADR-001 — Jetpack Compose as the application shell

* **Status:** Accepted
* **Date:** 2026-09-06
* **Affects:** every `:feature:*` module, `:core:designsystem`, `:app`

## Context

The IDE must run well on phones and tablets, in both orientations, in split-screen, with a
hardware keyboard and a mouse, and must not be a desktop layout squeezed onto a phone. Panel
composition (explorer / editor / panel / AI) changes shape by window size class.

## Decision

Jetpack Compose with Material 3, plus `androidx.compose.material3.adaptive` and
`androidx.window` for window size classes. Navigation via `navigation-compose`.

## Rationale

* Adaptive layout is a first-class concern in Compose; the same state drives a bottom-tab
  phone layout and a three-pane tablet layout without a parallel view hierarchy.
* Avoids a Fragment/View/Compose hybrid, which is where this class of app usually accretes
  its worst complexity.
* Recomposition-scoped state fits an editor shell where many small panes update independently.

## Structural rules this ADR imposes

1. **No god Activity.** `MainActivity` sets content and nothing else. All routing lives in a
   navigation graph owned by `:app`.
2. **Composables are pure functions of state.** They receive state and emit events. They do not
   own coroutine scopes, do not call repositories, and do not perform I/O.
3. **ViewModels expose a single immutable UI-state type per screen** as a `StateFlow`, and
   depend only on interfaces.
4. **`:core:designsystem` owns all colour, type and spacing tokens.** No feature module defines
   a raw colour. This is what makes editor theming and, later, extension-provided themes
   tractable.
5. Every screen must be usable at 320 dp width and must reflow rather than clip.

## Consequences

* Compose UI tests are the primary UI test vehicle (`ui-test-junit4`).
* Compose compiler cost is real, so `mobileforge.android.compose` is a *separate* convention
  plugin — Android modules without UI (`:core:data`, `:core:filesystem`) never apply it and
  never pay for it.
* Material 3 gives dynamic colour on Android 12+; the editor theme is derived from the same
  token set so the WebView and native chrome do not visually diverge.
