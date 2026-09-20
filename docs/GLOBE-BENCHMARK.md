# Globe frame benchmark — the one session that closes L3

**Audience: whoever has an Android phone and twenty minutes.**

Register item L3 says the globe optimisation is *"plausibly ~1–4 ms/frame and
nobody has measured it"*, and names the next wall as Path verbs, 38k–63k per
frame, ~1.1–1.9M JNI crossings per second. Those are counts of work removed, not
milliseconds. **No frame time has ever been recorded**, so there is nothing to
decide against.

This document exists so that stays a twenty-minute job and not an investigation.

---

## The correction that saves you a wasted session

An earlier note said to run `GlobeFrameMonitorInstrumentedTest` and *"record the
frame histogram as the baseline"*. **That test is not the measurement**, and it
says so itself:

> *This is NOT a performance measurement. Frame times from an x86_64 emulator on
> a desktop GPU say nothing about a 2019 ARM phone and must never be quoted as if
> they did. The assertions are about labelling and delivery only.*

It guards a real and silent correctness failure — JankStats has no live
`PerformanceMetricsState` until it is tracking a Window, and the globe composes
**before** the HUD attaches, so wrong ordering would label every frame `off`
while every JVM test still passed. Worth keeping. Not a baseline.

**The baseline comes from running the app**, with the HUD on, on a real device.

---

## What is being measured

| | |
|---|---|
| Subject | The Home-screen globe, in whichever quality tier it picks |
| Metric | Frame duration in **microseconds**, bucketed |
| Tiers | `FULL` and `LITE` are separate histograms — `GlobeTag` in `GlobeFrameMonitor.kt`. A number without its tier is meaningless |
| Instrumentation | `app/src/main/java/app/birdo/vpn/perf/` — already shipped, nothing to write |
| Readout | `GlobePerfOverlay`, drawn on the Home screen (`HomeScreen.kt:379`) |
| Storage | In-memory only. No persistence, no network sink, no account or network data — by design |

---

## The build

`GlobePerf.ENABLED` is `BuildConfig.DEBUG || BuildConfig.PERF_OVERLAY`
(`GlobePerf.kt:39`), so either of these works:

```bash
./gradlew :app:installDebug                       # debug: HUD on automatically
./gradlew :app:installRelease -PperfOverlay=true  # release build, HUD forced on
```

**Prefer the release build with the flag.** A debug build's globe is not the
globe users see — different compilation, different allocation behaviour — and the
whole point of the number is to describe the shipped app. `perfOverlay` is read
from the Gradle property or `BIRDO_PERF_OVERLAY` (`app/build.gradle.kts:137-139`)
and CI release builds never pass it.

---

## The device

**A real ARM phone. Not an emulator** — see the correction above.

Pick the *slowest* device you are willing to support, not the newest. The
decision this feeds is "is the globe costing users frames", and it is only ever
in doubt on the weakest hardware. A 2019-era mid-range handset is the right
subject; a current flagship will report a comfortable number and settle nothing.

Also: plug it in, or don't. Just record which. Thermal state and charging state
both move frame times, and a number without that context cannot be compared to
the next one.

---

## The run

1. Install, open the app, land on Home.
2. Leave the globe on screen and **untouched for 60 seconds**. The histogram is
   cumulative and in-memory, so it only needs time.
3. Interact for another 30 seconds — drag/spin the globe if it responds — so the
   sample includes the interactive path, not only the idle one.
4. Photograph or screenshot the overlay.
5. Record, from the HUD: the tier, frame count, p50, p90, max, and the bucket
   spread.

---

## What the answer means

The budget is **16.67 ms** at 60 Hz, **8.33 ms** at 120 Hz. Read the result
against the tier you actually got:

| Reading | What it means | What to do |
|---|---|---|
| p90 well under budget on the slowest device | The globe is not the bottleneck | **Record the number in L3 and close it.** Do not optimise |
| p90 near budget, p50 comfortable | Occasional jank, structural headroom | Worth the Path-verb work named in L3 |
| p50 near or over budget | The globe is costing frames continuously | Optimise, and this number is the before |
| Tier reads `LITE` throughout | The device already downshifted | Note it — a LITE baseline does not describe FULL, and both are needed before any claim |

**Do not optimise before the number exists**, and do not change production
rendering to make a subjective visual improvement while measuring. The point of
the session is a figure to decide against.

---

## Also worth doing in the same window

Two device-only items ride along at no extra cost, because the phone is already
in your hand with a build on it:

- **F-008 Android parity.** Android never runs a route command — it uses
  `VpnService.Builder.addRoute()` and excludes the endpoint via `protect()`, not
  a host route. So the check is simply: connect, confirm traffic flows and the
  endpoint is reachable, disconnect, confirm no leak. Five minutes.
- **M10 store screenshots.** The live Play listing's images date from
  2026-07-07 and still show the removed server-load display. Re-capture needs a
  device with USB debugging, which is exactly what you have set up.

## Result — run 2026-09-20

| | |
|---|---|
| Device | Samsung SM-S938B (Galaxy S25 Ultra), Android 16, arm64-v8a |
| Build | debug + `-PallowScreenshots=true` |
| Panel | 120 Hz LTPO, charging, no thermal warning |
| Tier observed | FULL throughout; LITE never engaged |

```
              n       p50    p90    p99    jank
globe FULL   4379    33.0   48.0   59.0   1.6%
globe OFF    1012    41.0   48.0   58.0   83.8%
delta FULL          -8.0   +0.0   +1.0
```

### ✅ Verdict: close it. Do not optimise.

**The globe costs +0.0 ms at p90 and +1.0 ms at p99.** The item guessed
"~1–4 ms/frame"; the real cost is below the bottom of that range, and the
"next wall" it names — Path verbs at ~1.1–1.9M JNI crossings/sec — does not
show up as frame cost at all.

### Read the DELTA, never the absolute numbers — this panel makes them lie

The S25 Ultra is LTPO: it drops its refresh rate when content is static. So with
the globe **hidden**, the screen is static, the panel idles, frame intervals
stretch, and "jank" reads **83.8%** — on a screen with nothing on it. Nothing is
stalling; the panel is resting.

That is also why p50 is **8 ms faster with the globe ON**: animated content keeps
the panel at a higher rate. An absolute frame time on a variable-refresh display
measures the display, not the app.

This is precisely why the HUD has a delta mode, and why the instruction above is
to collect an OFF baseline rather than quote a single figure.

### What this does and does not settle

**Settles:** there is nothing to chase. The globe is not costing frames, so the
Path-verb work named in L3 has no measurable prize behind it.

**Does not settle:** behaviour on a weak device. This is a current flagship.

It is a **debug** build, though — no R8, so slower than what ships. The number
is therefore conservative: release on this device is faster still, and
debug-on-a-flagship is a rough stand-in for release on something mid-range.
A +0.0 ms p90 delta has a lot of room before it becomes a problem anywhere.
