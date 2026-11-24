# HybridGuard: rules for the implementing model

HybridGuard is the phone-only monitoring app built from HybridCrown
(the Android Auto dashboard) and HybridDash (the logger and research app),
the author's other projects. Read `README.md` first.

## Product rules

- **Watch the cooling, unattended.** The OBD adapter (any ELM327-compatible
  Bluetooth Classic one; never name or assume a brand) stays plugged in and
  sleeps some minutes after the car is switched off. The app finds the car by
  itself, monitors while READY, and goes back to waiting. No touching the
  phone while driving.
- **Never wake or load a parked car.** Before a session, only `ATRV` (the
  adapter's own voltage) is sent; OBD requests start only when the 12 V
  shows the car is READY (setting, default 13.2 V). Never send a bare CR to
  the adapter (an ELM327 repeats its last command).
- **Everything customisable**: thresholds, levels, delays, sounds, values in
  the notification and the log, intervals, retention. New options go in
  `Settings` with a default; old saved settings must keep loading.
- Not Android Auto compatible (that is HybridCrown).

## Hard rules (carried over)

- **Read-only on the car.** Only services `01`, `09`, `21`, `22` (`3E` if
  ever needed), enforced by `ReadOnlyPolicy` and its test. Never `04`, `10`,
  `11`, `14`, `27`, `28`, `2E`, `2F`, `30`, `31`, `3B`, `85`.
- **No car-specific knowledge in Kotlin.** Car models are profile folders
  (`core-data/src/main/resources/vehicles/<id>/`); UI, alarms and logs use
  roles, never signal ids. More Toyota models will be added this way
  (Corolla 2020+, C-HR, ...), picked by VIN with per-year `dbgfilter`.
  The model year is not in most European VINs: when nothing gives it, the
  app asks the owner (remembered per VIN); never hard-code a year for a
  prefix shared by several years.
- **Public repository.** No real VINs (use made-up ones), Bluetooth
  addresses, names or local paths in code, tests, docs or commit messages.
- Do not invent PIDs or formulas (docs/02 tags: confirmed / untested /
  unknown). Alarm thresholds are owner choices, written in `alarms.json`
  notes; optional checks ship with `"enabled": false`.
- The whole app must run without a car: the simulated car and its
  scenarios (Settings → OBD adapter → Simulated car) are kept working.

## Conventions

- Kotlin, Jetpack Compose, coroutines/Flow, Gradle Kotlin DSL, minSdk 26.
- `:core-obd` and `:core-data` are pure Kotlin/JVM and unit-tested;
  Android code lives in `:app`.
- Units: metric.
- Charts follow the dataviz rules: one unit per chart (no dual axes), at
  most three lines, legend, alarm limits dashed in status colours.

## Workflow

- When a car test contradicts the docs, update the doc in the same change.
- Run `./gradlew test assembleDebug` before committing.
- **Changes reach `master` only through pull requests** (a branch
  protection rule on GitHub, enforced for admins too, blocks direct pushes,
  force-pushes and deleting it, and requires the `build` check to pass on
  an up-to-date branch with all review conversations resolved). Work on a
  branch from `master`, push every commit to that branch straight away,
  and open a pull request (`gh pr create`). The GitHub repo
  (`XOWN3RRX/HybridGuard`) is `origin`. If a push is rejected, stop and
  report it: never force-push.
