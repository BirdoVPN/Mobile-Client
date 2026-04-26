## Why iOS CI has been silently broken

Every push to `main` (and every Android tag) since the iOS workflow was added has produced a run with conclusion = `failure`, **0 jobs**, and the message *"This run likely failed because of a workflow file issue."*

`actionlint` nails it:

```
.github/workflows/ios.yml:196:29: context "runner" is not allowed here.
available contexts are "github", "inputs", "matrix", "needs", "secrets", "strategy", "vars".
```

The `runner` context is only available at **step** level, not in a **job-level** `env:`. The line:

```yaml
KEYCHAIN_PATH: ${{ runner.temp }}/birdo-build.keychain-db
```

made the entire workflow file invalid, so none of the four jobs ever spawned — including `shared-framework`, `build-ios`, and `test-ios` which we actually want running on every PR.

## Fix

Drop `KEYCHAIN_PATH` from the job `env:` and add a first step `Set keychain path` that does:

```bash
echo "KEYCHAIN_PATH=$RUNNER_TEMP/birdo-build.keychain-db" >> "$GITHUB_ENV"
```

so every subsequent step sees it.

## Validation

- `actionlint` now passes on every workflow file (exit 0).
- This PR itself will be the first iOS workflow run that actually spawns jobs — we'll see whether the KMM shared framework, the SwiftUI app, and the simulator tests all build cleanly. (Code-signing / TestFlight upload still gated on `ios-v*` tags.)
