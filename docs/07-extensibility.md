# 07. Extensibility: more car models, and control features later

Two requirements from the owner (2026-11-13):

1. Other models (Toyota C-HR and so on) may be added later.
2. The app is read-only now, but should later be able to do things such as
   lock the doors when the car starts moving and unlock when shifted to P.

Both must be designed in from Phase 1 so nothing is rewritten later.

## 1. Vehicle profiles

**Rule: no car-specific knowledge in Kotlin code.** Everything that differs
between models lives in a *vehicle profile*, a folder of data files. Adding a
model means adding a folder, not changing code.

`:core-data` is a plain JVM module (so the offline decoder can run on a PC),
so profiles are JVM resources rather than Android assets. They are packaged
into the APK all the same.

```
core-data/src/main/resources/vehicles/
  index.json                       list of profiles + how to match them
  sae-j1979/signals.json           standard PIDs, shared by every car
  toyota-common/signals.json       optional: Toyota-wide definitions
  toyota-corolla-hybrid-e210/
    profile.json
    signals.json                   OBDb signalset (base)
    overrides.json                 corrections found on real cars
    derived.json                   which signals feed which dashboard role
    display.json                   optional: gauge full scale for the dashboards
    warmup.json                    optional: warm-up advice thresholds
    alarms.json                    optional: cooling alarms (docs/02 C.7)
    battery.json                   optional: Battery tab pack type and health-check limits
    discovery.json                 optional: ECUs and id ranges for the discovery scan
  toyota-chr-hybrid/               (future) same layout
```

`profile.json`:

```json
{
  "id": "toyota-corolla-hybrid-e210",
  "name": "Toyota Corolla Hybrid (E210)",
  "version": 2,
  "years": [2019, 2026],
  "match": {
    "vinPrefixes": [ "SB1K93BE" ],
    "manual": true
  },
  "bus": { "protocol": "ISO15765_11_500" },
  "includes": ["sae-j1979"],
  "ecus": { "engine": "7E0", "hybrid": "7D2", "battery": "747",
            "meter": "7C0", "ac": "7C4", "brake": "7B0" }
}
```

`vinPrefixes` entries are a plain prefix string, or an object
`{ "prefix": "...", "modelYear": 2019 }` for the rare prefix that really
fixes one model year. The longest matching prefix wins; a car with no match
gets the fallback profile and the owner can pick one by hand, remembered for
that VIN. Only prefixes seen on real cars are listed: `SB1K93BE` (`SB1` =
Toyota Motor Manufacturing UK; 1.8 hybrid hatchback) was seen on a 2019 car,
and the same prefix is used for every model year, so it carries no year.
Other E210 variants (2.0, Touring Sports, other plants) are added when their
VINs are known. `version` is bumped whenever the profile's data
changes; it is written to log metadata and invalidates cached capability
probes. `toyota-common` (from `OBDb/Toyota`) and probe-based `variants`
(e.g. pack type by the number of `747 22 182E` blocks) are not implemented
yet: add them when a second Toyota model needs them.

`overrides.json` holds corrections found on real cars, applied to the
combined signalsets before parsing. Every key must match something, so a
typo fails at load time:

```json
{
  "note": "free text: which log proved each change",
  "addCommands":    [ { "...": "an OBDb command" } ],
  "commands":       { "7D2:221F9A": { "freq": 0.25 } },
  "signals":        { "COROLLAHYBRID_HVBAT_C": { "fmt": { "mul": -1 } } },
  "disableSignals": [ "SOME_SIGNAL_ID" ]
}
```

Patches are shallow merges (`fmt` merges into the existing `fmt`); `null`
removes a field.

### Roles decouple the dashboard from the car

The UI and derived metrics never ask for `COROLLAHYBRID_HVBAT_V`. They ask for
a **role**, and `derived.json` maps roles to that profile's signals, in order
of preference:

```json
{
  "speed":          ["VSS"],
  "engineRpm":      ["RPM"],
  "hvVoltage":      ["COROLLAHYBRID_HVBAT_V"],
  "hvCurrent":      ["COROLLAHYBRID_HVBAT_C"],
  "soc":            ["COROLLAHYBRID_SOC", "BAT_SOC"],
  "fuelLitres":     ["COROLLAHYBRID_FLV"],
  "gear":           ["COROLLAHYBRID_SHIFT_POS", "COROLLAHYBRID_SHIFT_POS_FLAGS"],
  "ready":          ["COROLLAHYBRID_READY"]
}
```

Roles are a fixed enum in `:core-data` (`Role.SPEED`, `Role.HV_VOLTAGE`, ...).
A role with no working signal on the current car is simply shown as
unavailable; the dashboard hides or greys that slot. This is also how the
missing Corolla items (EV flag, `S4`) are handled today.

### Selection and probing

- On connect, read the VIN (`09 02`) and match against the profiles listed in
  `index.json`. If there is no match, or the user prefers, choose the profile
  manually. Remember the choice per VIN.
- **Model year.** North American (and many other) VINs give it at position
  10; European VINs need not, and Toyota's European ones put `0` there. The
  model year is taken from, in order: the owner's entry for this VIN, the
  matching `vinPrefixes` entry's `modelYear`, then VIN position 10. It
  matters: OBDb commands can be limited to model years, and with an unknown
  year those are left out. So when none of them gives it and the profile has
  a `years` range, a session assumes the profile's first year (flagged in
  the session state and the log), the app posts a quiet "Which model year is
  your car?" notification, and asks in the app (a list of the profile's
  years). The answer is remembered for that VIN and a running session starts
  again with it. Settings → Car model → "Model year of your car" changes it.
- The Phase 1 **capability probe** result is cached per VIN: it records which
  requests this particular car answers, so the scheduler never polls dead
  requests and role fallbacks resolve automatically.
- An unknown car still works with the `sae-j1979` profile alone (generic
  dashboard), and the logger + discovery scan are the tool for building its
  profile.

### Why this is cheap for Toyota models

The OBDb project that supplied the Corolla data has a repository per model in
the same JSON format (for example `OBDb/Toyota-C-HR`, `OBDb/Toyota-Prius`,
`OBDb/Toyota-RAV4`; exact names to check when needed). Toyota hybrids of the
same generation share the hybrid system, so most `7D2` definitions are expected
to carry over. Expected, not verified: confirm with a log from the actual car.

### What the implementer must do in Phase 1 because of this

- Loader takes a profile id; nothing reads a hard-coded asset path.
- Signal ids are opaque strings; no `if (id == "COROLLAHYBRID_...")` anywhere.
- ECU headers, protocol and polling periods come from the profile.
- Log metadata records profile id, profile version and VIN.
- Unit test: load a tiny fake second profile and run the full pipeline on it.

## 2. Control features (future, not in Phases 1 to 3)

### Honest assessment

Reading and writing are very different. Reading is safe and documented by the
community. Sending commands to the body computer is not publicly documented for
this car, differs per model and year, and a wrong command can have real
effects. Nothing below has been verified on a Corolla; it is a design
placeholder plus a research task.

**Check first whether the car already does this.** Toyota has built-in
"speed-linked door lock" and "shift-to-P linked unlock" customisation
settings. On many Toyotas they can be switched on from the settings menu, by
a procedure in the owner's manual, or by a dealer / Techstream. If the 2019
Corolla offers them, that is the right solution for this particular example:
it works without the phone, and without an adapter plugged in. To verify in
the owner's manual (section on automatic door locking and unlocking).

If the app does it, there are two technical routes:

| Route | What it is | Trade-off |
|---|---|---|
| A. Change the car's own setting once | Write the customisation value in the body ECU (the same thing Techstream does) | One-time write; afterwards the car does it natively. Needs the exact identifier and value for this ECU. |
| B. App triggers each time | App watches speed/gear and sends a "lock" actuator command (diagnostic active test) | Needs the phone connected and the app running every drive; sends commands while driving; may need a diagnostic session and periodic tester-present, which can interfere with normal ECU behaviour. |

Route A is preferred where the setting exists. Route B is the general
mechanism for automations the car has no setting for.

How the command identifiers would be found: capture what a diagnostic tool
sends when performing the same action (Techstream active test / customise),
or community sources. This is a separate research phase, done parked.

### Architecture hooks to put in now

So that control can be added without touching the read path:

1. **Two separate gateways in `:core-obd`.**
   - `ReadGateway`: services `01 09 21 22` (+ `3E`). Used by everything in
     Phases 1 to 3.
   - `CommandGateway`: interface defined now, **no implementation shipped**.
     The Phase 1 "allow-list throws" test stays and applies to `ReadGateway`.
2. **Commands are data in the profile, never free-form bytes.** Future
   `actions.json` per vehicle:
   ```json
   { "id": "lockAllDoors", "ecu": "body", "session": "extended",
     "request": "30 11 00 80", "expect": "70 11",
     "verifiedOn": ["VIN-prefix, year"], "risk": "low" }
   ```
   (Bytes are an illustrative placeholder, not a real command.) Only actions
   marked verified for the matched profile can be sent. No UI for typing raw
   hex.
3. **Scheduler has a priority lane.** A command pauses polling, runs its
   sequence (session, command, confirm, return to default session), then
   polling resumes. Design the scheduler queue with this in mind now.
4. **Automation engine in `:core-data`** (later): rules of the form
   *trigger* (role-based condition with debounce, e.g. `speed > 10 km/h for
   2 s`, `gear == P`) → *action id*. Because triggers use roles, a rule works
   on any model whose profile supplies those roles and that action. Note the
   door example needs the `gear` role, found on the Corolla in 2026-11 (docs/02 C3).
5. **Safety rules for any future action.**
   - Off by default; each automation enabled explicitly by the user.
   - Each action declares when it is allowed (for example lock: any time;
     unlock: only when speed = 0).
   - Never: anything touching brakes, steering, airbags, powertrain,
     immobiliser or key programming. Body/comfort actions only.
   - Rate limit (one action per N seconds), and stop all automations after
     any unexpected negative response.
   - Every sent command is written to the log.

### What does not change

Phases 1 to 3 stay strictly read-only. Control is "Phase 4", starting with
research, and only after the gear signal (found, docs/02 C3) has been reconfirmed.

## 3. Dashboards are modules

More dashboard designs will come; the owner wants to try different ones.
The layers keep them independent of the car and of the screen:

```
signals (profile) -> roles (derived.json) -> DashboardEngine -> DashboardState -> Dashboard.draw(Canvas)
```

- `DashboardState` (`:core-data`) holds every value a design can show,
  already derived and in display units (kW, °C, km/l), null when not
  available. Designs never see signals or roles.
- `Dashboard` (`:dashboard` module, Android, no Compose): `id`, `name`,
  `description`, `prefersDark`, and `draw(canvas, width, height, state,
  theme)`. Designs lay out in their own design units and scale with
  `fitDesign` (fixed shape) or, better, `fitFlexible` (fixed height,
  width grows: pin parts to the right edge so wide car screens are
  filled). Shared pieces (speed arc, power bars, status line, icons)
  are in `Components.kt` / `Draw.kt`; a design may use them or not.
- `Dashboards.all` is the registry; the Dash tab's menu lists it and saves
  the choice. The first entry is the default.
- `DashboardTheme.LIGHT` / `DARK` hold the colours; the host chooses
  (setting, system dark mode, or the design's preference).

To add a design: create `MyDashboard : Dashboard` in `:dashboard`, add it
to `Dashboards.all`, and run `./gradlew :dashboard:testDebugUnitTest`: the
screenshot test renders every design in every state, light and dark, at
phone (2400 × 1080) and head-unit (800 × 480) size. If a design needs a
value `DashboardState` does not have yet, add the field there (and a role
in `Role` plus `derived.json` entries if it comes from a new signal).

Car facts the designs need are profile data, not Kotlin: gauge full scale
in `display.json` (`speedMaxKmh`, `enginePowerMaxKw`, `batteryPowerMaxKw`)
and warm-up advice thresholds in `warmup.json` (`oilWarmFrom`,
`oilOkFrom`, `coolantWarmFrom`, `coolantOkFrom`,
`coldMaxAcceleratorPct`, `coldMaxRpm`; docs/02 C.6). A profile without
them gets generic ranges and no warm-up advice.

The Battery tab's facts are in `battery.json`: `chemistry` (`lithiumIon`
or `nimh`; leave it out when the profile covers cars with either pack, and
the app tells it from the cell voltages), `cells` (optional), and one limit
set per pack type (`lithiumIon`, `nimh`: `spreadWarnMv`, `spreadBadMv`,
`resistanceRatioWarn`, `resistanceRatioBad`, `tempSpreadWarnC`,
`tempSpreadBadC`, `intakeDeltaWarnC`, `restCurrentA`), each with a note on
where the values come from. Its roles (`hvCellVoltage`, `hvCellResistance`,
`hvBlockVoltage`, `hvSocMax`, `hvSocMin`, `hvChargeLimit`,
`hvDischargeLimit`, `hvIntakeTemp`, `hvFanLevel`, `auxCurrent`, `auxTemp`,
`auxCapacity`) are marked `battery` in `Role`: polled only while the tab is
open. A NiMH pack that reports block voltages maps them to
`hvBlockVoltage` (or to `hvCellVoltage` if that is all it gives).
