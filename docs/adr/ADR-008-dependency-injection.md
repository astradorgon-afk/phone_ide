# ADR-008 — Manual dependency injection

* **Status:** Accepted (revisit at the end of Phase 5)
* **Date:** 2026-09-06
* **Affects:** `:app`, every feature module

## Context

The brief makes mockable architecture mandatory: `ProcessExecutor`, `FileSystem`,
`GitService`, `AiProvider`, `ExtensionHost` and `NetworkClient` must all be replaceable in
tests. That requires disciplined dependency inversion. It does not, by itself, require a DI
framework.

## Options

| Option | Assessment |
|---|---|
| **Hilt** | The Android-standard answer. Costs an annotation processor on every module, meaningful build-time overhead across 13 modules, and pulls Android-aware annotations toward modules we are deliberately keeping pure-JVM. |
| **Koin** | No codegen, but service-locator semantics mean a missing binding is a *runtime* failure — exactly the class of bug an IDE cannot afford at startup. |
| **Manual constructor injection + `AppContainer`** | **Chosen.** No codegen, no plugin, no runtime resolution. A missing dependency is a compile error. |

## Decision

Constructor injection everywhere, with a hand-written `AppContainer` at the composition root
in `:app`, and per-feature container interfaces where a feature needs several collaborators.

```kotlin
interface AppContainer {
    val dispatchers: AppDispatchers
    val workspaceRepository: WorkspaceRepository
    val fileSystem: WorkspaceFileSystem
    val pathValidator: PathValidator
    val runtimeProbe: RuntimeCapabilityProbe
}
```

ViewModels take their dependencies as constructor parameters and are created through an
explicit `ViewModelProvider.Factory`.

## Rationale

* **Compile-time safety.** A missing binding fails the build, not the app on a user's phone.
* **Build speed.** No annotation processor across 13 modules. KSP is already required for Room;
  adding a second processor to every module compounds.
* **Keeps the domain pure.** `:core:model`, `:core:common`, `:core:security`, `:runtime:api` and
  `:ai:api` stay free of *any* framework annotation, which is what makes their tests run as
  plain JVM tests in milliseconds.
* **Fakes are trivial.** A test constructs the object with fakes. There is no test-component
  ceremony and no `@Install`/`@Uninstall` dance.
* **Readable.** The full object graph is one file a new contributor can read top to bottom.

## Consequences

* Wiring is written by hand and grows as the app grows. Accepted while the graph is shallow.
* **Explicit revisit point:** at the end of Phase 5, if `AppContainer` exceeds roughly 40
  entries or the graph acquires deep conditional scoping, re-evaluate Hilt. Because every
  dependency is already inverted through an interface, that migration would be mechanical —
  which is the property that makes deferring the decision safe rather than reckless.
