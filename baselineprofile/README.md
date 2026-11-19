# :baselineprofile

Baseline-profile generator and frame-timing benchmarks for `:app`. Nothing here ships; it only
runs on a connected phone or emulator.

## What is committed

`app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt` are generated
files. Release builds compile them into `assets/dexopt/baseline.prof`, and
`androidx.profileinstaller` installs that on first launch for APKs that do not come from Play, so
the tab shell and the detail screens start out ahead-of-time compiled instead of interpreted.

## Regenerate the profile

After a large change to the tab shell or a detail screen, with an API 33+ emulator or phone
connected (API 28+ if it is rooted):

```
./gradlew :app:generateReleaseBaselineProfile
```

It builds `nonMinifiedRelease` (installed as `com.laksh.coffee.bench`, so the real app and its data
are never touched), seeds sample brews through the debug `DebugTools`, walks every tab and the
recipe, brew-setup and brew-detail screens, and rewrites the two files above. Commit them.

## Benchmarks

```
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

`TransitionBenchmarks` times tab switches (with and without the profile) and opening and closing a
recipe and a brew with `FrameTimingMetric`. Read `frameDurationCpuMs` and `frameOverrunMs`; the
targets at 120 Hz are in `docs/plan/jank-audit.md` section 5.1. On an emulator add

```
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

and treat the numbers as relative only: an emulator renders in software.

## Quick check without the module

```
adb shell cmd package compile -f -m speed-profile com.laksh.coffee
adb shell dumpsys gfxinfo com.laksh.coffee reset
# switch tabs twenty times
adb shell dumpsys gfxinfo com.laksh.coffee
```

## How the variants are wired

`app/build.gradle.kts` declares the plugin's two build types itself (`benchmarkRelease`,
`nonMinifiedRelease`) so they get the `.bench` id suffix and the debug signing key. The plugin
copies release's source directories into them, which would bring the no-op `DebugTools`; a
`finalizeDsl` block swaps that for `src/debug/java`, which is why the journeys can seed data.
