# A test must not make the engine end the test JVM

**Surfaced 2026-10-26 while fixing the localfs `root_path` check (gkrost/unidrive-cli#106).**

## Problem

`Main` calls `System.exit` on several refusals (a bad config, a mode refusal; see `Main.kt`). A test that runs
`Main` in the test JVM with a configuration `Main` refuses ends the Gradle test worker, and the failure names no
test:

```
Test Executor finished with non-zero exit value 1
```

Every test behind the one that died in that worker is reported as not run, and the log has no stack trace, because
nothing threw.

It happened twice in one change. Once the engine refused a localfs profile whose `root_path` equals or overlaps a
`sync_root` (#106, by design), `ConfigValueExitCodeTest` and `VerifyRemoteObjectsTest` still built exactly that
profile (`root_path` equal to `sync_root`) and killed `:app:cli:test`.

## How to find the test

The Gradle output does not say which one. Temporarily wrap `System.exit` (a `SecurityManager` is gone on current JDKs, so
route the exit through a seam you add for the run) so that it appends a stack trace to a scratch file before it ends the
JVM; the first frame of the test in that trace is the culprit. Do not commit the wrapper.

## What to do

- A test config must be one `Main` accepts. When an engine rule starts refusing a configuration, search the tests for
  that configuration (here: every `type = "localfs"` fixture with a `root_path`) in the same change.
- A test that means to exercise a refusal must not do it through the process exit; keep such a case out of the shared
  test worker (a child process) until `Main` takes the exit function as a parameter.
- Better, and not done: inject the exit function into `Main`, so that a refusal in a test is a return value and not the end
  of the worker.

## Related

A red-by-design pull request (failing tests on purpose) fails `check`, `check-runtime-jdk` and `check-windows`, and when
it also touches main code a `detekt` error (for example `LargeClass` on `Reconciler.kt`, seen on
[#115](https://github.com/gkrost/unidrive-cli/pull/115)) ends `check` before any test runs. Read the first red step,
not the last.
