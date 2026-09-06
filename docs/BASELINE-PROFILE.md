# Baseline profile

The release APK and AAB ship an ART baseline profile. It is **recorded by
running the app**, never written by hand.

- Source of truth: `app/src/release/generated/baselineProfiles/baseline-prof.txt`
  (plus `startup-prof.txt`, which AGP uses for dex layout)
- Recorded by: `:app:generateReleaseBaselineProfile` → `scripts/generate-baseline-profile.sh`
- Producer module: `baselineprofile/`
- Regenerated on a schedule by `.github/workflows/baseline-profile.yml`

## Why "never by hand" is a rule and not a preference

Android AOT-compiles and pre-pages exactly the classes and methods the profile
names. A profile is therefore a *budget*, not a wish list: entries that are not
on the real startup path spend that budget on the wrong code, and cold start gets
**slower**. A fabricated profile compiles, packages, installs, passes every gate
in this repository, ships, and looks identical to a good one from the outside.

That is not hypothetical here. Issue #358 was opened after two attempts shipped a
hand-written guess, one of them accompanied by startup numbers that did not
correspond to any build on disk. Both were rejected in review, which was the only
thing standing between them and production.

The guards that now exist, and what each one can actually prove:

| Guard | Proves |
| --- | --- |
| `BaselineProfileIntegrityTest` (unit test) | the committed file has the shape and scale of a recording: tens of thousands of ART descriptors, ≥70% of them full method signatures, ≥100 of the app's own classes, plus Compose/Hilt/Lifecycle entries that a real launch cannot avoid |
| `scripts/verify_android_release_apk.py` | the compiled profile is inside the shipped **APK** (`assets/dexopt/baseline.prof`) **and AAB** (`BUNDLE-METADATA/…/baseline.prof`), and is not the empty-but-well-formed file AGP writes when the source profile is missing |
| `.github/workflows/baseline-profile.yml` | a fresh recording still describes the same startup path, monthly |

None of them can prove provenance. They exist to make the cheap forgeries fail
and to stop the profile decaying silently once it is right.

The monthly check compares rule **sets**, not the file, and allows a churn
threshold rather than demanding an exact match. That is measured, not assumed:
two back-to-back recordings on the same emulator differed by 28 added and 9
removed rules out of 22,687 unchanged — **0.163% churn**, all of it in
timing-dependent background work. An exact-match gate would fail every month and
would be switched off, which is how a profile goes stale. Only the same-machine
figure is measured; the threshold in `scripts/compare_baseline_profiles.py` is
deliberately an order of magnitude looser, sized to catch a startup path that has
genuinely moved.

## Recording one

```bash
scripts/generate-baseline-profile.sh              # Gradle Managed Device (default)
scripts/generate-baseline-profile.sh --connected  # a rooted/userdebug phone over adb
```

Then commit whatever changed under
`app/src/release/generated/baselineProfiles/`, and **say in the commit message
which device it was recorded on**. A profile whose provenance is not written down
is indistinguishable from a guess six months later.

### The managed device

`baselineprofile/build.gradle.kts` declares one Gradle Managed Device,
`profileGenDevice`: **Pixel 6, API 34, `google_apis`, x86_64**. AGP downloads the
system image, boots it headless, records, and tears it down, so the recording
does not depend on anybody's desk.

Two of those choices are load-bearing:

- **`google_apis`, not `google_apis_playstore`.** Recording pulls the ART profile
  out of `/data/misc/profiles`, which needs `adb root`. Play-store images are
  production-signed and refuse it, so the run fails at the last step, after the
  emulator has already booted — the most expensive possible way to find out.
- **`google_apis`, not `aosp_atd`.** ATD images boot faster and also allow root,
  but carry no Google Play services, and this app touches Play Integrity and Play
  Billing on the startup path.

`testedAbi` is pinned to `x86_64`. AGP 9.4 warns that the default flips to
`arm64-v8a` in AGP 10, which would start recording under NDK translation — a
different execution path from anything we ship.

### Hardware requirements

An emulator needs hardware virtualisation.

| Host | Works? |
| --- | --- |
| Linux with `/dev/kvm` (GitHub `ubuntu-latest`, self-hosted) | yes — CI grants access via a udev rule; the workflow asserts `/dev/kvm` is writable and fails loudly if not |
| Windows with WHPX or Hyper-V | yes — the committed profile was recorded this way |
| Intel macOS | yes (HVF) |
| Apple Silicon | no usable x86_64 emulation — use `--connected` |
| GitHub `macos-*` runners | no — use `ubuntu-latest` |

## What the recording covers

`BaselineProfileGenerator.startup` walks the cold-start path and stops: process
start, `BirdoApp` (Hilt graph, Sentry init), `MainActivity`, the Compose runtime,
and the first screen the nav graph resolves to for a signed-out user.

It deliberately does **not** sign in, connect a tunnel or navigate deeper.
Padding a profile with screens most launches never reach is precisely the
mechanism by which a profile makes startup worse.

## Measuring

`StartupBenchmark` measures cold start twice on the same device in the same run —
`CompilationMode.None()` against `CompilationMode.Partial(BaselineProfileMode.Require)`.

**It cannot be measured on the managed device, and that is not a gap to work
around.** androidx.benchmark aborts on any emulator with
`ERRORS (not suppressed): EMULATOR`, because an improvement measured on an
emulator can be a regression on real hardware. Recording a profile on an emulator
is fine — the class list is the same. Measuring one there is not.

So a real number needs a phone:

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.birdo.vpn.baselineprofile.StartupBenchmark
```

The suppression flag is deliberately **not** set in `baselineprofile/build.gradle.kts`.
Setting it there would make emulator numbers the default output of a task named
"benchmark". Opt in per invocation if you want a same-machine sanity check, and
then label the result an emulator figure:

```bash
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

**No device measurement has been taken for the profile currently committed.** It
was recorded, and verified to reach both shipped artifacts, and shipped on that
basis. Any future claim of a startup improvement must name the device it was
measured on — the absence of that name is what gave the last two attempts away.

## Build-graph notes

Two things in `app/build.gradle.kts` exist only because of this feature, and both
carry an assertion that fails if the assumption behind them stops holding:

1. **Signing.** `androidx.baselineprofile` clones `release` into
   `nonMinifiedRelease` and `benchmarkRelease`, which inherit the production
   signing config. They are re-pointed at the debug key so that recording a
   profile never requires the upload keystore — otherwise only the key holder
   could regenerate it, which is how the previous profile became unreproducible.
2. **Source-set repair.** Under AGP 9 the plugin copies the `release` source set
   into its new build types while the `kotlin` convention directory is still an
   unresolved Gradle `Provider`, and KSP later fails with
   `Illegal char <?> at index 40: …/app/provider(?)`. A `finalizeDsl` block
   restates the four directories explicitly. Delete it once the upstream plugin
   stops doing that.
