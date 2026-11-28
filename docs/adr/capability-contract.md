# Provider capability contract

## Context

The `CloudProvider` SPI is wide: `list`, `read`, `write`, `delete`, `delta`, `deltaWithShared`, `handleWebhookCallback`, `share`, `quota`, etc. In practice, adapters implement a subset:

- `deltaWithShared()` — OneDrive only; absent on Internxt.
- `handleWebhookCallback()` — OneDrive only; absent on Internxt.
- `share()` — OneDrive (sharing links); absent on Internxt.

Without a contract, upstream code cannot tell "feature not supported" from "feature supported and returned nothing", and feature work orphans because it cannot know who to call.

## Decision

Formalize a capability contract on `CloudProvider` in two tiers (amended 2026-11-27, gkrost/unidrive-cli#268):

**Tier 1: `Capability`, the optional verbs.** A verb the adapter may not offer at all (share, list views, permanent
delete, the remote trash, delta with shared items, ...).

1. `CloudProvider.capabilities(): Set<Capability>` is mandatory and enumerates what the adapter actually supports.
2. An optional method whose capability is not declared answers `CapabilityResult.Unsupported(<capability>, <reason>)`
   instead of silently no-op-ing. The destructive verbs (`deletePermanently`, `listTrash`, `emptyTrash`, `purgeTrash`)
   throw `UnsupportedCapabilityException` instead; moving them onto `CapabilityResult` changes every CLI and IPC caller
   and waits for a future SPI version.

**Tier 2: behaviour descriptors, members of `CloudProvider`.** How a verb every adapter has behaves on this drive:
`deltaIsFullListing`, `allowsDuplicateNames`, `caseSensitivity`, `hashAlgorithm()`, `reportsItemVersions`,
`reportsContentIds`, and `conditionalWrite`. A descriptor is a value, not a set member, because it has more than two
answers or qualifies a verb rather than naming one.

3. **A safety-relevant descriptor defaults to the safe side.** An adapter that declares nothing is taken to have the
   weaker behaviour, so a forgotten override costs a refusal or a lost optimisation, never data: `caseSensitivity`
   folds, `allowsDuplicateNames` is true, so the id-addressed defaults (`deleteById`, `moveById`, ...) refuse their
   path fallback until the adapter declares its names unique, `reloadCredentials` refuses until the adapter states its own non-interactive reload, and
   `conditionalWrite` is `NONE`.
4. **`conditionalWrite`** (#268, #47 item 4) declares, for `upload` and for `delete`, what the write does with the
   `ifMatchETag` it is handed: `ATOMIC` (the server checks it in the write), `EMULATED` (the provider reads the current
   token, then writes; a concurrent write in between is not caught) or `NONE` (ignored). Declared today: OneDrive and
   Dropbox atomic for both; S3 upload atomic on AWS, emulated elsewhere, delete emulated; Internxt upload emulated,
   delete none (it trashes); localfs none.

**The conformance suite holds both tiers to the behaviour.** `CloudProviderConformanceTest` (`:app:core` testFixtures)
runs in every provider module: an undeclared capability answers `Unsupported`, and a declared write guard refuses a
stale token while an undeclared one writes. The declaration is the provider's, not a knob of its test module.

The `CapabilityRequired` annotations on `:app:sync` tools that this ADR first proposed were never built and are dropped:
callers test `Capability.X in provider.capabilities()` or read the descriptor where they branch.

## Consequences

- Callers branch on `Unsupported` explicitly; no silent degradation.
- Adding a new optional method requires every adapter to either implement it or declare `Unsupported`.
- Adding a descriptor needs a safe default and a conformance contract that pins the declared value to the behaviour.
- The engine can tell a real, an emulated and an absent write guard apart. What it does with `EMULATED` or `NONE`
  (refuse a modify whose remote changed, re-check itself, or only report it) is not decided here.
- Breaking change to the SPI shape; acceptable on the slim branch where there are only two implementations.

## Alternatives considered

- Runtime `throw UnsupportedOperationException` — loses type safety; callers still crash.
- Folding the descriptors into `Capability` (`DuplicateNames`, `ItemVersions`, `ConditionalWrite(...)`): one
  mechanism, but churn across every caller for no safety gain once the conformance suite checks the behaviour
  (#268, rejected 2026-11-21).
- Separate subinterfaces (e.g. `Shareable`, `Webhookable`) — Kotlin doesn't handle dynamic subinterface discovery cleanly; increases SPI churn.
