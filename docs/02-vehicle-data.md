# 02. Vehicle data

Car: Toyota Corolla Hybrid 2019 (E210). Bus: CAN 500 kbit/s, 11-bit IDs
(ELM protocol 6).

Status tags:

- **C19** = reported working on 2019 cars (Pelican "Supported for 2019").
- **U19** = in the list, untested on 2019 ("Tester needed"). Logger confirms.
- **?** = not in any list found; needs discovery.

Decode rule (OBDb format): take `len` bits starting at bit `bix` of the data
bytes **after** the echoed service and identifier, big-endian, bit 0 = MSB of
the first data byte; `value = raw * mul / div + add` (`mul`, `div` default 1,
`add` default 0; `sign: true` means two's complement).

Further rules from the OBDb reference decoder (`OBDb/.schemas`,
`python/can/signals.py`), implemented in `:core-data` `SignalFormat.kt`:

- The value is clamped to `[min, max]` when `max > min` (`min` defaults to 0,
  so a signed signal without `min` cannot go negative).
- At or past `nullmin` / `nullmax` the signal has no value (e.g. `0xFF` =
  "not available").
- `blsb: true` reads the value's bytes little-endian.
- `map` turns the signal into an enumeration; an unmapped code has no value.
- A command `filter` (`from` / `to` / `years`) selects model years. When the
  model year is unknown, filtered commands are left out.

Request = header (`hdr`) + command. Response comes from `hdr + 8`
(`7E0→7E8`, `7D2→7DA`, `747→74F`, `7C0→7C8`, `7C4→7CC`, `7B0→7B8`).
Standard service `01` requests go to the functional address `7DF`, or directly
to the engine ECU `7E0`.

## A. Mockup → data source

| Mockup item | Source | Request | Decode | Status |
|---|---|---|---|---|
| Speed (km/h) | SAE | `01 0D` | len 8 | C19 |
| Engine rpm | SAE | `01 0C` | len 16, /4 | C19 |
| Coolant °C | SAE | `01 05` | len 8, −40 | C19 |
| Oil °C | SAE | `01 5C` | len 8, −40 | C19 per Pelican, but **no value on the owner's car** (see note) |
| Oil °C (alt) | 700 | `22 1F5C`, then `22 107B` byte 24 | len 8, −40 | U19 leads: no value in two drives, dropped |
| Engine power (kW) | computed | `01 62` + `01 63` + rpm | see C.1 | C19 inputs |
| Battery % (SOC) | SAE | `01 5B` | len 8, ×100/255 | C19 |
| Battery % (alt) | 7D2 | `22 1F5B` | len 8, ×100/255 | C19 |
| HV voltage | 7D2 | `22 1F9A` | bix 16, len 16, ×156.25/10000 | U19 |
| HV current (A) | 7D2 | `22 1F9A` | bix 32, len 16, signed, /10 | U19 |
| Battery power (kW) | computed | V × A | see C.2 | U19 inputs |
| Battery temp °C | 7D2 | `22 1829` (max) / `22 182A` (min) | len 8, −50 | U19 |
| Battery temp (alt) | 747 | `22 1814` | 16 sensors, len 16 each, ×19531/5000000 −50 | U19 |
| Fuel (litres) | 7C0 | `22 1022` | len 16, /100 | C19 |
| Ambient °C | 7C4 | `21 3D` adjusted ambient (A/C) | len 8, ×81.6/255 −30.8 | U19 (preferred since 2026-11-23) |
| Ambient (raw) | SAE | `01 46` | len 8, −40 | C19, raw sensor: heat-soaks when standing (see note) |
| Ambient (alt) | 7D2 | `22 1F46` | len 8, −40 | U19 |
| 12 V | SAE | `01 42` | len 16, /1000 | C19 |
| 12 V (alt) | 7D2 | `22 1F42` | len 16, /1000 | U19 |
| Power bars (kW) | computed | engine power, battery power | see C.3 | mixed |
| Accelerator % | 7D2 | `22 1F49` | len 8, ×100/255 | U19 |
| avg km/l | computed | `01 9D` fuel rate + speed | see C.4 | C19 inputs |
| EV % of trip | computed | rpm = 0 while moving | see C.5 | C19 inputs |
| Trip km | computed | integrate speed (or odometer `7D2 22 0103`) | | C19 |
| `EV` badge | computed | rpm = 0 and car READY | see C.5 | C19 inputs |
| Gear `P`/`R`/`N`/`D`/`B` | 7D2 | `22 1061` | byte 0 enum: 0 P, 2 R, 4 N, 6 D, 8 B (byte 1: one bit per position) | found 2026-11-13 (discovery, see C3); `S4` unknown |
| `bar` gauge | ? | meaning unclear, see 06 | | ? |

## B. Catalog

### B.1 Standard SAE J1979 (service 01, header 7DF or 7E0), all C19 unless noted

| PID | Signal | Decode | Unit |
|---|---|---|---|
| `04` | Calculated load | len 8, ×100/255 | % |
| `05` | Coolant temp | len 8, −40 | °C |
| `06` / `07` | Short / long fuel trim | len 8, ×100/128 −100 | % |
| `0C` | Engine RPM | len 16, /4 | rpm |
| `0D` | Vehicle speed | len 8 | km/h |
| `0E` | Timing advance | len 8, /2 −64 | ° |
| `0F` | Intake air temp | len 8, −40 | °C |
| `10` | MAF | len 16, /100 | g/s |
| `11` | Throttle | len 8, ×100/255 | % |
| `1F` | Time since engine start | len 16 | s |
| `2F` | Fuel level | len 8, ×100/255 | % (not seen on Pelican; test) |
| `33` | Barometric pressure | len 8 | kPa |
| `3C` | Catalyst temp B1S1 | len 16, /10 −40 | °C |
| `42` | Control module voltage | len 16, /1000 | V |
| `43` | Absolute load | len 16, ×100/255 | % |
| `46` | Ambient air temp | len 8, −40 | °C |
| `4C` | Commanded throttle | len 8, ×100/255 | % |
| `5B` | Hybrid battery SOC | len 8, ×100/255 | % |
| `5C` | Engine oil temp | len 8, −40 | °C (2019, 2024) |
| `5E` | Engine fuel rate | len 16, /20 | L/h (only 2024–25 reported; test) |
| `62` | Actual engine torque | len 8, −125 | % |
| `63` | Engine reference torque | len 16 | N·m |
| `8E` | Engine friction torque | len 8, −125 | % |
| `9D` | Engine fuel rate / vehicle fuel rate | bix 0 and bix 16, len 16, /50 | g/s |
| `9E` | Exhaust flow | len 16, /5 | kg/h |
| `A6` | Odometer | len 32, /10 | km (unverified) |

First thing the logger does: read the supported-PID bitmaps (`01 00`, `01 20`,
`01 40`, `01 60`, `01 80`, `01 A0`) and log them.

### B.2 Hybrid control ECU, header 7D2 (response 7DA), service 22

All U19 except `1F5B`. `fc` = multi-frame response expected.

| DID | Signal | bix | len | Formula | Unit |
|---|---|---|---|---|---|
| `0102` | Key cycle count | 0 | 16 | raw | |
| `0103` fc | Odometer | 8 | 24 | raw | km |
| `1002` | Aux battery voltage | 0 | 16 | ×61/50000 | V |
| `1040` | Transaxle oil temp | 16 | 8 | −40 | °C |
| `1069` | Regen brake torque requested / actual | 0 / 16 | 16 | /8 −4096 | (unit doubtful) |
| `106C` fc | HV SOC max / min | 8 / 16 | 8 | /2 | % |
| `106E` | A/C power consumption | 0 | 8 | /20 | kW |
| `1075` | Wheel speed FR / FL / RR / RL | 0/16/32/48 | 16 | /100 −327.68 | km/h |
| `10A1` | Motor RPM | 0 | 16 | −32768 | rpm |
| `10A1` | Motor torque | 48 | 16 | /8 −4096 | N·m |
| `10A4` | Generator inverter temp | 0 | 8 | −40 | °C |
| `10A5` | Motor inverter temp / after IG-on / max | 0/8/16 | 8 | −40 | °C |
| `10A7` | Boost converter temp upper / lower | 0 / 8 | 8 | −40 | °C |
| `10A8` | Generator inverter temp / after IG-on / max | 0/8/16 | 8 | −40 | °C |
| `10B0` | VL (before boost) / VH (after boost) | 0 / 16 | 16 | /2 | V |
| `10B2` | Inverter coolant temp | 0 | 8 | −40 | °C |
| `1112` | Inverter water pump duty | 0 | 16 | ×15259/5000000 | % |
| `1039` | Inverter water pump speed (**confirmed 2019**, D item 11; not in OBDb, which has an AWD torque ratio here) | 16 | 16 | raw | rpm |
| `111C` | DC-DC output current | 0 | 8 | ×25/32 | A |
| `111D` | DC-DC low-side voltage | 0 | 8 | ×1953/25000 | V |
| `111E` | DC-DC high-side voltage | 0 | 16 | ×1220703/10000000 | V |
| `15EE` | 12 V battery voltage | 0 | 8 | ×0.31448 +0.0125 | V |
| `15F7` | 12 V battery current | 0 | 16 | ×38.147/10000 −125 | A |
| `15F8` | 12 V battery temp | 0 | 16 | /10 −40 | °C |
| `1802` | Hybrid battery fan | 16 | 8 | /2 | % |
| `1829` / `182A` | HV battery max / min temp | 0 | 8 | −50 | °C |
| `1F05` | Coolant temp | 0 | 8 | −40 | °C |
| `1F0D` fc | Vehicle speed | 0 | 8 | raw | km/h |
| `1F42` | 12 V smoothed | 0 | 16 | /1000 | V |
| `1F46` | Ambient temp | 0 | 8 | −40 | °C |
| `1F49` / `1F4A` | Accelerator pedal 1 / 2 | 0 | 8 | ×100/255 | % |
| `1F5B` fc | State of charge (**C19**) | 0 | 8 | ×100/255 | % |
| `1F9A` | HV battery voltage | 16 | 16 | ×156.25/10000 | V |
| `1F9A` | HV battery current | 32 | 16 signed | /10 | A |

### B.3 Battery ECU, header 747 (response 74F), service 22, U19

| DID | Signal | Layout | Formula | Unit |
|---|---|---|---|---|
| `0103` fc | Odometer | bix 8, len 24 | raw | km |
| `1814` | Battery temperature 01..16 | 16 bits each, bix 16×(n−1) | ×19531/5000000 −50 | °C |
| `1824` | Battery heater temp | len 8 | −50 | °C |
| `182E` fc | Cell voltage 01..96 | 16 bits each, bix 16×(n−1) | ×5/65535 | V |

The owner's car returns 56 cell voltages (lithium-ion pack, docs/02 C5).

Battery tab requests (profile 11, read only while the tab is open; from
the HybridMaster profile, see the note in `overrides.json`):

| DID | Signal | Layout | Formula | Unit | Status |
|---|---|---|---|---|---|
| `1807` | Charge / discharge power limit (Win / Wout) | 16 bits each | /100 −327.68 | kW | answers (−28.1 / +14.8 kW parked, cold); U19 |
| `2802` | Battery fan level | len 8 | raw | | answers (00, engine stopped); U19 |
| `181A` | Pack 1 / 2 average voltage (cells 1-28 / 29-56) | 16 bits each | ×5/65535 | V | **confirmed 2019**: 3.548 / 3.556 V, the mean of cells 1-28 / 29-56 read 0.7 s apart was 3.551 / 3.555 V (HybridLogger session-20261113-174656) |
| `182F` fc | Cell internal resistance 01..56 | 8 bits each | ×25.6/100 | mΩ | answers (3.6-4.1 mΩ); formula U19: to confirm against the cell voltage change over a current step (both read with the Battery tab open) |

### B.4 Body / meter, header 7C0 (response 7C8)

| Cmd | Signal | Decode | Unit | Status |
|---|---|---|---|---|
| `22 1022` fc | Fuel remaining | len 16, /100 | L | C19 |
| `22 1021` | B+ voltage | len 8, /10 | V | U19 |
| `21 29` | Fuel level volume | len 8, ×500/3785 | US gal | U19 |
| `21 23` | Coolant temp | len 8, /2 | °C | U19 |
| `21 21` | Speed | len 8 | km/h | U19 |
| `21 41` | Distance since oil change | len 8, ×2514600/15625 | km | U19 |

### B.5 Others (all U19; lower priority)

- **A/C `7C4`**: `21 21` cabin temp (×63.75/255 −6.5), `21 22` ambient
  (×89.25/255 −23.3), `21 49` compressor rpm (len 16), `21 29` set temp.
- **Brake/ABS `7B0`**: `21 03` wheel speeds, `21 47` lateral/longitudinal g
  (signed, /50), yaw, steering angle (bix 24, len 16, /10 −3276.8), `21 48`
  regen request / operation (len 16 each, N·m), `21 07` wheel cylinder
  pressure (len 8, /51, reported as volts), `21 1F` stop light / parking brake.
- **Transmission `7E1`**: `21 82`, `21 D9` fluid temps (len 16, /256 −40).
- **TPMS `750`** uses extended addressing (`2A`): `21 30` pressures
  (len 8 each, /58 −0.5 bar). Skip until everything else works.

**Oil, car test 2026-11-23 (HybridGuard log, 35 min drive):** `01 5C`
gave no value at all, while `01 05` coolant and the other `01` PIDs via `7DF`
did. The "supported for 2019" note comes from the Pelican list, not from
this car. The app now falls back to the engine ECU `700` (which answers:
the engine water pump `22 1043` comes from it) with two leads,
`22 1F5C` and `22 107B` byte 24, both untested: compare with the coolant
once warm (oil usually ends a few degrees above it).
**Outcome (second drive, 2026-11-23 21:01):** still no oil value with all
three requests. The owner chose to drop oil temperature: the Corolla profile
maps `oilTemp` to nothing, the optional oil alarm is gone, and the engine is
watched through the coolant checks. The warm-up advice uses coolant alone.

**Ambient, car test 2026-11-23 (HybridGuard log, 0-2 °C outside):** `01 46`
read 2-5 °C while driving but climbed to 26 °C over ~15 min standing in
traffic (engine cycling, coolant ~60 °C), and fell back to ~5 °C within a
minute of driving. It is the raw bumper sensor warmed by the radiator, not
the cabin. The dash and the A/C use a filtered value; the app now prefers
the A/C ECU's adjusted ambient `7C4 21 3D` (formula from the signal set,
not yet checked on the car) and falls back to `01 46`.

The full machine-readable source is
`https://raw.githubusercontent.com/OBDb/Toyota-Corolla-Hybrid/main/signalsets/v3/default.json`.
The implementer should download it into the repo as the base catalog (check its
licence first, expected CC BY-SA 4.0, and add attribution) and layer a local
`overrides.json` on top for corrections found on this car.

## C. Derived values

1. **Engine power.** `torque_Nm = (TQ_ACT% − TQ_FR%) / 100 × TQ_REF`, with
   `01 62`, `01 8E`, `01 63` (read `63` once). `kW = torque_Nm × rpm / 9549`.
   Shown in kW (owner's choice 2026-11-14, was CV). Fallback if torque
   PIDs misbehave: estimate from MAF.
2. **Battery power.** `kW = V × A / 1000` from `22 1F9A` (same response, so
   the two are time-aligned). Sign convention (positive = discharge?) must be
   confirmed on the car: mockup shows −0.3 CV while cruising in EV.
3. **Power bars.** Owner's choices (2026-11-13, relabelled 2026-11-14):
   left bar "ICE" = engine power from the bottom; right bar "EV" =
   battery power from the middle (up, blue = giving power; down, green =
   charging). Under each bar its own power in whole kW. No value is shown
   with a sign (owner's choice 2026-11-14): battery power and HV current
   are shown as positive numbers, blue with the word "using" when the
   battery gives power, green with "charging" when it takes power, plain
   with "battery" within ±1 kW. The mockup's total value and pedal % (and the pedal
   marker) were dropped: they made the bars hard to read. Bar full scale
   comes from the profile's `display.json` (`enginePowerMaxKw`,
   `batteryPowerMaxKw`).
4. **Average km/l.** Integrate fuel `g/s` from `01 9D` over time; litres =
   grams / 745 (petrol density, tune against real fill-ups). km from integrated
   speed. `km/l = km / litres`. Mockup's 95.8 km/l is consistent with a mostly
   electric short trip.
5. **Drive mode / EV %.** A pill at the top says what drives the car
   (owner's choice 2026-11-14, replaces the EV / ENG pill). Engine off is
   `rpm < 100`; the battery counts as idle within ±1 kW (a display
   choice).

   | Pill | Rule |
   |---|---|
   | EV (green) | engine off (and READY or moving) |
   | REGEN (green) | engine off, battery charging, moving |
   | ICE (orange) | engine on, battery idle |
   | BOOST (orange, blue) | engine on, battery giving power |
   | CHARGE (green) | engine on, battery charging |

   Braking with the engine still running (for example while it warms
   up) shows CHARGE, not REGEN: the two can't be told apart without a
   brake or pedal signal. EV % = distance covered with the engine off /
   total trip distance.

6. **Warm-up advice (COLD / WARM / GOOD).** Owner's choice
   2026-11-13, replacing the estimated HSD stage S1-S4 (background
   below). Next to the gear, a filled pill tells the driver how hard the
   car may be driven while the engine warms up:

   | Level | Pill | Meaning | Rule (Corolla profile) |
   |---|---|---|---|
   | COLD | blue, "COLD" | Slow driving only, light pedal | oil < 50 °C or coolant < 40 °C |
   | WARM | amber, "WARM" | Normal traffic, no full throttle or kickdown | below OK |
   | OK | green, "GOOD" | Use the car fully | oil ≥ 80 °C and coolant ≥ 70 °C |

   The third level reads "GOOD" (owner, 2026-11-14; until 0.7.5 it showed
   the oil temperature): a word like the other two, and "HOT" would read
   as overheating. The oil temperature itself can go on a tile.

   The hybrid ECU's own warm-up stage is still not readable (checked
   2026-11-14 against HybridLogger's findings): the only lead is the
   engine ECU's warm-up flag `700 22 10C8`, set from engine start to about
   36-40 °C coolant in one cold start, meaning not settled (HybridLogger
   docs/02 C8). So the advice stays on the temperature rule above; when a
   logged stage or flag is understood, it can drive the pill instead.

   - Each level needs every available input to reach it (the colder
     one decides). Oil temperature (`01 5C`, confirmed 2019) is the main
     input, because oil warms more slowly than coolant and is what
     protects the engine. A car without oil temperature uses coolant
     alone.
   - The level only goes up during a drive: engine-off (EV) stretches
     cool the engine a little but do not make it cold again. It resets
     when neither temperature arrives any more (car off, link lost), so
     the next start begins from the real temperatures.
   - While COLD, a pedal at or above 50 % or the engine at or above
     3000 rpm turns the pill red. This is a reminder only: nothing is
     sent to the car.
   - All thresholds are **profile data** (`warmup.json`: `oilWarmFrom`,
     `oilOkFrom`, `coolantWarmFrom`, `coolantOkFrom`,
     `coldMaxAcceleratorPct`, `coldMaxRpm`). They are general
     engine-care values chosen by the owner, not Toyota figures, and can
     be changed there.
   - Oil temperature is now polled on every design, every 5 s (profile
     override; it was 1 s and detail-only).

   **Background: HSD warm-up stages S1-S4 (not used).** The S0-S4 names
   come from enthusiasts (PriusChat, Japanese Prius owners), not Toyota.
   They describe how the hybrid ECU runs the engine for emissions and EV
   permission (catalyst heating, an "idle check" before S4), not when
   the engine can be loaded. Thresholds are published for Gen 2 and
   Gen 3 HSD only (Gen 3: S1 below ~40 °C coolant, S2 40-70 °C, S3 from
   ~70 °C until the idle check, S4 after it). No verified table exists
   for Gen 4 (this car's 2ZR-FXE). Gen 4 also adds exhaust heat
   recovery, and owners report the first engine stop as low as ~36 °C.
   The ECU decides with inputs we cannot read (catalyst state, the idle
   check), so the app does not show a stage. Coolant: on this car
   `7D2 22 1F05` and `7C4 21 26` both answer and agree within 1 °C with
   `01 05` via `7DF`. First data point (parked session 2026-11-13):
   coolant 31 °C at start, crossed ~40 °C after about 3 min 10 s, 44 °C
   after 5 min.

   **Cold-start drive (2026-11-13, ~10 °C outside, 0.1.2 log
   `session-20261113-163953`), contradicts the S2 description on this car:**
   - Engine started about 20 s after READY at 21 °C coolant, idled at
     ~1300 rpm with ignition timing retarded to -8 °
     (`01 0E`), catalyst (`01 3C`) rising 54 -> 300 °C in 30 s and ~525 °C
     after 3 min: matches the S1 description (late ignition to heat the
     catalyst), though it lasted ~3 min, not ~50 s.
   - The engine ran without stopping, also while driving, until it stopped
     **while moving at 33 km/h** with coolant at **40 °C** and the catalyst
     at ~520 °C, and stayed off: the car then drove electrically at
     10-35 km/h for about 2 minutes with coolant at 39-41 °C.
   - So at ~40 °C this car already allowed EV driving, which the S2
     description says is not possible. One event only: the trigger may be
     coolant temperature, catalyst temperature or time. This is one
     reason the stage estimate was dropped for the warm-up advice above.

7. **Cooling alarms (app 0.10.0, owner's request 2026-11-14).** The
   electric water pumps of Toyota hybrids are a known weak point: owners
   report pumps that stop, or turn without moving water (broken plastic
   impeller), sometimes without any warning light, and engines lost to it.
   When the engine ECU does notice, it sets P261B / P261C ("coolant pump
   control", reported below about 900 rpm; Toyota T-SB-0143-10 for the
   2010 Prius pump) and, by owner reports, shuts the engine down near
   105 °C. The inverter pump fails too (P0A93 on the Gen 2 Prius); its
   loop is separate from the engine's (C8 in HybridLogger: inverter
   coolant 5 -> 12 °C while the engine coolant reached 43 °C).

   **Checks** (profile data, `alarms.json`; thresholds chosen 2026-11-14,
   not Toyota figures, to be tuned from logs):

   | Check | Level | Rule (Corolla profile) |
   |---|---|---|
   | Engine water pump stopped | STOP | engine ≥ 500 rpm for 10 s, ECU target ≥ 500 rpm, pump < 300 rpm for 10 s |
   | Engine water pump slow | CAUTION | as above, target ≥ 1000 rpm, pump < half the target for 30 s |
   | Engine coolant very hot | STOP | ≥ 110 °C for 10 s |
   | Engine coolant hot | CAUTION | ≥ 103 °C for 20 s |
   | Engine coolant rising fast | CAUTION | ≥ 97 °C and rising ≥ 4 °C a minute over 60 s |
   | Inverter water pump stopped | STOP | READY for 15 s, pump < 1000 rpm for 10 s (profile 9, app 0.11.0) |
   | Inverter water pump slow | CAUTION | READY for 15 s, pump < 2500 rpm for 30 s |
   | Inverter coolant very hot | STOP | ≥ 75 °C for 20 s |
   | Inverter coolant hot | CAUTION | ≥ 65 °C for 30 s |
   | Inverter coolant rising fast | CAUTION | ≥ 45 °C and rising ≥ 2 °C a minute over 3 min |

   - The pump rule uses what was logged on this car (`700 22 1043`): the
     pump runs only while the engine runs, reads 0 within one sample of
     every engine stop, reaches 1500 rpm within 6 s of a start, and the
     target is always 25-125 rpm above the actual. So the engine must
     run for 10 s before the pump is judged, and an EV stretch never
     raises it.
   - A pump that turns but moves no water reads normal, so the coolant
     checks stand behind it: the rising-fast rule warns before the hot
     ones. Normal warm-up climbs fast too (5 -> 43 °C in a few minutes)
     but below 97 °C.
   - **Inverter pump** (app 0.11.0): `7D2 22 1039` bytes 2-3 in rpm,
     *confirmed 2019* (D item 11). Logged in HybridLogger: 160 rpm before
     READY, about 4250-4300 rpm in READY with the inverter coolant below
     about 15 °C, 3432-3485 rpm from 16 °C on, engine running or not. The
     car reports no target speed for it (the duty `1112` is refused), so
     the checks use fixed speeds well below anything logged in READY, and
     only after 15 s of READY (spin-up). Long drives and a hot inverter
     are not logged yet: if a healthy pump ever runs below 2500 rpm, lower
     the caution. The inverter coolant checks stay behind it, for a pump
     that turns but moves no water. The coolant (`7D2 22 10B2`) is read
     every 5 s instead of 1 s. Normal values in warm weather are not
     logged yet (one hobbyist report: about 50 °C); the 65 / 75 °C limits
     need a summer log.
   - **Reading:** while the alarms are on, the engine pump (`700 22 1043`,
     every 2 s), the inverter pump (`7D2 22 1039`, every 2 s) and the
     inverter coolant (every 5 s) are polled all the time, on top of the
     coolant (`01 05`, every 0.5 s) and READY (`7D2 22 1076`, every 1 s).
     Three small requests, so the dashboard stays quick. Checks are run on every reading; each needs
     its condition to hold 10-30 s (one odd reply is not an alarm), so a
     stopped pump is reported 10-12 s after it stops. An alarm clears 20 s
     after its condition is gone (temperatures 2 °C below the threshold).
     A check whose values are not arriving raises nothing.
   - **Telling the driver:** a STOP alarm takes the whole car and phone
     screen in red ("STOP THE CAR SAFELY", what, the readings), plays
     three beeps and says it (navigation-guidance audio, so Android Auto
     plays it on the car speakers over the music), and posts a heads-up
     notification that also shows while another app (maps) is on the car
     screen. It repeats every 30 s until OK is tapped; after OK a red band
     stays on top of the dashboard and it is said again every 5 minutes
     while it lasts. A CAUTION is an amber band, two beeps and the
     message, once. The car menu's **Alarms** button switches them off or
     on, plays a test alarm and lists what is being watched. No alarms
     on simulated data (random values).

## C2. Results on the owner's car (first parked session, 2026-11-13)

2019 Corolla Hybrid, READY, engine running (cold), parked. Capability probe
of every catalog request once (log `session-20261113-150820.jsonl.gz`).
"Answers" means a positive reply; whether the OBDb formula gives the right
value is a separate check (see the values below).

| ECU | Answers | Refused / silent |
|---|---|---|
| `7E0` engine | nothing on its own address; **answers on the broadcast `7DF`** (reply `7E8`) | all `01` PIDs `NO DATA` when sent to `7E0` |
| `7DF` broadcast `01 00` | replies from `7E8`, `7EA`, `7EB`, `7EE` | |
| `7D2` hybrid | `22` `0102 0103 1002 1039 1040 1069 106C 106E 1075 10A1 10A4 10A5 10A7 10B0 10B2 15EE 15F7 15F8 1F05 1F0D 1F42 1F46 1F49 1F4A 1F5B 1F9A` | NRC 31 (no such DID): `106A 107E 10A2 10A6 10A8 1112 111C 111D 111E 111F 1609 1802 1814 1829 182A` |
| `747` HV battery | `22` `0103 1814 182E` | NRC 31: `1824` |
| `7C0` meter | `22` `1021 1022` | NRC 11 (service 21 not supported): all `21 xx` |
| `7C4` A/C | `21` `21 22 26 3C 3D 41 43 44 49 4A 4B 4C 53` | NRC 12: `24 29 AF B1` |
| `7B0` brake | `21` `03 05 06 1D 1F 21 3C 3D 42 46 47 48 58 5A 5F A3 A6 BC BE` | NRC 12: `07 A1`; NRC 11: `22 1006` |
| `7B1`, `7D0`, `7E1` | none (`NO DATA` on their own address) | |
| `780`, `7B3` | none | NRC 12 / 11 |

So, from section B: HV voltage and current (`1F9A`), SOC (`1F5B`), fuel
litres (`7C0 22 1022`), battery temps (`747 22 1814`), accelerator
(`1F49/1F4A`) all answer. Not available on this car: HV battery max/min
temp `1829/182A`, battery fan `1802`, DC-DC values `111C-111E`, the meter's
`21 xx` values, A/C set temperature `21 29`, wheel-cylinder pressure
`7B0 21 07`. Battery temps `1814` work on `747`, not on `7D2`.

First decoded values (plausibility only, not yet checked against the car's
own displays): HV battery 204.0 V and 1.2 A, SOC 43.9 %, fuel 36.4 L.

Second session (app 0.1.2, cold start and a short drive up to 37 km/h,
generic profile only because the Corolla profile was not selected):

- Through `7DF` the engine ECU answers VIN (`09 02`) and the PID bitmaps;
  50 standard PIDs probed, 43 answer, including speed `0D`, rpm `0C`,
  coolant `05`, timing `0E`, MAF `10`, catalyst temps `3C/3E`, actual and
  friction torque `62/8E` (reference torque `63` answered in the probe),
  fuel rate `9D` (0-0.9 g/s), time since start `1F`. So engine power (C.1)
  and consumption (C.4) have their inputs.
- Speed `01 0D` agrees with phone GPS: mean difference -0.3 km/h over 182
  moving samples.
- VIN position 10 is `0`: this VIN does not encode the model year. The
  Corolla profile now matches the VIN prefix `SB1K93BE`, so the profile
  needs no entering by hand. (The year was first taken from that prefix
  entry as 2019; the prefix is shared by every model year, so the app now
  asks the owner once for the year instead: docs/07.)

Still open: the hybrid ECU values with the Corolla profile selected at the
new request rate, and the gear / EV flag (discovery).

## C3. Discovery scan on the owner's car (2026-11-13, app 0.2.0)

Log `session-20261113-174656`, READY, parked. The whole Corolla plan
(20480 requests) finished in 14 min: 7104 requests sent at 8.4 req/s, the
rest skipped by the rules (silent ECU, NRC 11). Every reply is in the log
as raw hex.

| ECU | Service 21 | Service 22 | Answered |
|---|---|---|---|
| `7D2` hybrid | NRC 11 | yes (others NRC 31) | 112 DIDs: `0100-0107`, `1000-10B7`, `1500`, `15E4-15FF`, `1800`, `181D`, `1F00-1FA6` |
| `747` battery | NRC 11 | yes (others NRC 31) | 70 DIDs: `0100-0105`, `1000-10B0`, `1800-182F`, `1F00-1F9A` |
| `7C0` meter | NRC 11 | yes (others NRC 31) | 25 DIDs: `0100-0105`, `1000-10A6`, `1100-11A1`, `1500`, `15A1`, `1600`, `1641` |
| `7B0` brake | yes (others NRC 12) | NRC 11 | 56 PIDs `00-F7` |
| `7C4` A/C | yes (others NRC 12) | NRC 11 | 42 PIDs `00-E2` |
| `7E0`, `7E1`, `7E2`, `7E3`, `7E6` | no reply on their own address (skipped after 16 requests) | | |

No identifier was refused with another code (no NRC 22 / 33). The full
lists are in the log (`discovery` command, docs/05 task 7).

The watch part of the same session could not find the gear: 242
identifiers at ~8 req/s made a round of ~35 s, while each gear was held
2-5 s, so during the shift sequence 183 identifiers were never read and
the rest once. Only weak leads (one read in D each, to recheck):
`7B0 21 5A` byte 0 went `10` -> `18` (bit `0x08`, not one of the OBDb
signals in that reply) and `7B0 21 A3` bytes 1 and 3 changed. Watch mode
was changed for the next try (docs/08 section 7): fewer ECUs per run,
normal polling paused, and a per-marker round counter.

**Second watch, same day (log `session-20261113-181624`, app 0.2.1):
shift position found.** Only `7D2` watched (112 identifiers, ~12 s
round), lever P R N D B D N R P with each position held 14-19 s, then
brake pedal and drive-mode markers. `7D2 22 1061` followed the lever in
every segment of both passes, with nothing else changing it:

| Lever | P | R | N | D | B |
|---|---|---|---|---|---|
| byte 0 | `00` | `02` | `04` | `06` | `08` |
| byte 1 | `01` | `02` | `04` | `08` | `10` |

Added to the Corolla profile (`overrides.json` `addCommands`,
`COROLLAHYBRID_SHIFT_POS` and `..._FLAGS`, role `gear`; profile version
3). To reconfirm in a normal drive log (and against a phone scan app's
display, docs/08 T1) before the dashboard relies on it.

**Independent match:** the OBDb signalset of the Toyota Venza Hybrid
(`OBDb/Toyota-Venza-Hybrid`, `signalsets/v3/default.json`, marked `dbg`
there) has the same request and the same codes: `7D2 22 1061`, byte 0,
`0` P, `2` R, `4` N, `6` D. Its map has no `B`; the Corolla's `8` = B is
from our log. So the hybrid ECU layout is shared across these TNGA
hybrids, and the Venza list is a good source of candidates for `7D2`.

From the same Venza list, a candidate for the **READY** flag (item 3 in
section D): `7D2 22 1076` bix 15 (byte 1, lowest bit), "Ready to drive".
The owner's car answers it with `0F 03` or `0F 0B` in both logs, always
in READY, so the bit was 1 as expected. **Confirmed 2019** in
session-20261114-101039: `0F 02` (bit 0) for the first 30 s with the
ignition ON and the 12 V at 11.5 V (not READY yet), then `0F 03` / `0F 07`
in READY (12 V at 14.2-14.4 V), and `0F 02` again at switch-off.
session-20261114-010856 ends with `0F 00` (switched off). Switching off
does not always give a 0: in session-20261114-161118 and -180641 the last
answer is `0F 03` and the next one is `NO DATA` (the hybrid ECU goes
silent), so the app takes READY as unknown after two failed replies in a
row instead of holding the last 1. In both, the engine ECU's 12 V
(`01 42`) still read 13.5-13.6 V right after switch-off: above the 13.2 V
READY voltage, which is why a new session waits for the voltage to rise
again. Also there: `7D2 22 1060`
accelerator pedal (`/2` %, read `00` while parked).

The READY candidate is in the Corolla profile since profile version 4
(`COROLLAHYBRID_READY`, role `ready`, polled every second), so every log
now records it; the open check is a session started with the ignition ON
but not READY.

Other leads from the same log, one session only, not added:

- `7D2 22 1035` byte 1 = `01` only in R; `22 10AC` / `22 10AD` byte 1 =
  `01` only in N; `22 104C` byte 1 also encodes the lever (P `61`, R `22`,
  N `44`, D `18`, B `58`).
- Brake pedal: `7D2 22 15FE` byte 1 bit `0x40` set while pressed (`58`
  pressed, `18` released). A switch, not a pressure.
- Drive mode: `7D2 22 181D` byte 1 bit `0x04` (bix 13) set only in Sport.
  Eco gave no difference from Normal on `7D2`: look on other ECUs.

## C4. Comparison with the OBDb Toyota Venza Hybrid signalset (2026-11-13)

`OBDb/Toyota-Venza-Hybrid` `signalsets/v3/default.json` (commit
`ed1d214`) has 307 requests and contains all 114 of the Corolla's. Shared
requests decode the same, except:

| Request | Corolla | Venza | On the owner's car |
|---|---|---|---|
| `7B0 21 21` speed | ×1.35 | ×1.28 | answers; only `00` seen (parked): compare with `01 0D` in a drive |
| `7B0 21 42` wheel acceleration | signed, ×1.56906 | unsigned, raw | answers; only `00` seen |
| `7B0 21 47` steering angle | max 3276 | max 3276.7 | same formula |
| `7C0 21 29`, `21 A7` | | | refused (meter has no service 21) |
| `750 22 1005` TPMS | bounds differ slightly | | not polled (extended addressing) |

Venza-only requests the owner's car answers (scan, docs C3), with what it
sent while parked in READY:

| Request | Venza signal | Reply | Decoded with the Venza formula |
|---|---|---|---|
| `7D2 22 1061` | shift position | | in the profile (C3) |
| `7D2 22 1076` | READY, bix 15 | `0F 03` | yes; in the profile (C3) |
| `7D2 22 1FA6` | odometer, len 32, /10 km | `00 10 FE E0` | 111382.4 km; matches `7D2 22 0103` (111382 km) in the same log |
| `7D2 22 15E8` | 12 V charge / discharge total (Ah, /10), READY-on hours (bix 80) | `00 00 15 40 00 00 08 F9 81 80 00 25 ...` | 544.0 Ah in, 229.7 Ah out, 37 h (low for a 2019 car: may count since a reset; unverified) |
| `7D2 22 15E5` | 12 V full charge capacity, /2 Ah | `6C` | 54 Ah (plausibility unknown) |
| `7D2 22 1060` | accelerator, /2 % | `00` | 0 % (parked); the profile already has `1F49` |
| `747 22 1F5B` | SOC from the battery ECU, /2.55 % | `45` / `49` | 27-29 %, same raw range as `7D2 22 1F5B` at that time |

Added to the Corolla profile in profile version 5 (app 0.2.4), definitions
copied unchanged except the id prefix (`COROLLAHYBRID_ODO_1FA6`,
`..._12V_AH_IN`, `..._12V_AH_OUT`, `..._READY_HOURS`, `..._12V_CAP`,
`..._APP_V2`, `..._SOC_747`). Only the odometer is checked against
another value; the others are *untested 2019* until compared with
something known (e.g. a phone scan app's display, or the 12 V totals
rising over drives).

The Venza list also has 82 requests on `700`, 11 on `701`, 51 service-21
requests on `7E0` and 32 on `7E2` (among them `7E2 21 C3`: MG1 / MG2 rpm
and torque, item 6 in section D). None of these can be tested yet:
`700` / `701` were not in the scan plan, and `7E0` / `7E2` do not answer
on their own address on this car (only through the `7DF` broadcast, where
the app sends services `01` and `09` only). The Venza is a different
hybrid generation (2.5 l, AWD), so these are candidates, not facts.

## C5. Comparison with the OBDb Toyota Prius signalset (2026-11-13)

`OBDb/Toyota-Prius` `signalsets/v3/default.json` (commit `6e7a842`), 268
requests, many limited to model years (`from 2016`, `from 2017`, ...).
109 of them are already in the Corolla profile and decode the same, except
the TPMS requests on `750` (not polled: extended addressing) and the SOC
on `747 22 1F5B`, which is written ×20/51 there: the same as /2.55.

The Prius list has **no** shift position, READY, odometer `1FA6` or 12 V
statistics on `7D2`: those came from the Venza list (C3, C4).

Prius-only requests the owner's car answers, all on the battery ECU `747`
(Prius filter `from 2017`), decoded from the scan log with the Prius
formulas:

| Request | Prius signal | Reply data | Decoded | Note |
|---|---|---|---|---|
| `747 22 1F9A` | HV battery current, bix 32, /10 A (unsigned) | `06 00 31 80 00 0C` | 1.2 A | same as `7D2 22 1F9A` at that time; the `7D2` one is signed, keep it |
| `747 22 180B` | battery intake air temperature 1 / 2, ×458/65535 −58 °F | `3D E3` | 52.7 °F (11.5 °C) | plausible (about 10 °C outside); only one value on this car |
| `747 22 1802` | battery fan, bix 16, /2 % | `00 00 00 00` | 0 % | parked, cold; `7D2 22 1802` is refused on this car |
| `747 22 1819` | "block average voltage" ×5/65536 (unit written °F, a slip) | `B6 0B B5 FB B6 1B` | 3.556 / 3.555 / 3.557 V | 3 values here, 5 in the Prius list; meaning unclear |
| `747 22 106E` | A/C power | `00` | | formula in the Prius list is broken (max = min) |

Added in profile version 6 (app 0.2.5): `747 22 1802` battery fan
(`COROLLAHYBRID_HYBRID_BAT_1_FAN_747`, unchanged) and `747 22 180B`
intake air temperature 1 (`COROLLAHYBRID_HYBRID_BAT_IAT_1`), converted
exactly from the Prius °F formula to °C: ×2290/589815 −50. The second
intake temperature is left out (this car sends 2 data bytes only). Both
*untested 2019*: the fan should rise when the pack is warm after a drive.

**Battery type.** In the same scan log `747 22 182E` (cell voltages,
already in the catalog) returned 112 data bytes: 56 values of 3.608 to
3.625 V. 56 × 3.62 V ≈ 203 V matches the HV voltage (204 V), so the
owner's car has the **56-cell lithium-ion** pack, not NiMH. Confirmed by
the owner.

The Prius `7E0` / `7E2` / `7E3` / `700` / `701` requests are mostly the
same as the Venza's and cannot be reached yet (C4).

## D. Values still to find

Gear / shift position was found by the discovery watch on 2026-11-13
(section C3). The values below are still open or need reconfirming, most
important first. Two ways to settle them:

- the app's discovery scan (Discover tab, app 0.2.0; how-to in docs/08
  section 7): sweep `21 xx` and `22 xxxx` on the ECUs in the profile's
  `discovery.json` while parked, then watch every identifier that answered
  while the owner shifts P → R → N → D → B, presses the EV button and
  changes drive mode, tapping a marker at each change. The offline tool
  (`discovery` command, docs/05 task 7) lists the reply bytes that change
  with the markers;
- a **phone scan app** (docs/08 section 6): its Toyota list shows which
  values exist on this car and their live values, and it can
  run custom `21` / `22` requests. A value seen there is a lead, not a
  confirmed PID: only a request and raw response that our own logger
  records counts as *confirmed 2019*.

| # | Value | Needed for | Status |
|---|---|---|---|
| 1 | Shift position (P / R / N / D / B) | gear on the dashboard, door rule (docs/07) | **found**: `7D2 22 1061` (C3), reconfirm in a drive |
| 2 | `S4` shown next to `D` in the mockup | dashboard | meaning unknown (docs/06 q6) |
| 3 | READY flag (hybrid system ready vs ignition on) | EV badge, session start | **confirmed 2019**: `7D2 22 1076` bix 15 (C3), 0 with the ignition ON not READY and at switch-off |
| 4 | EV mode button state | EV badge | ? (fallback: computed, C.5) |
| 5 | Drive mode (Eco / Normal / Sport) | dashboard (optional) | lead for Sport (`7D2 22 181D`, C3); Eco not seen on `7D2` |
| 6 | MG1 and MG2 rpm and torque separately | power flow | `7D2 22 10A1` answers; which motor it is is unknown |
| 7 | Brake pressure in real units | `bar` gauge, if that is its meaning (docs/06 q4) | `7B0 21 07` refused; brake pedal switch lead `7D2 22 15FE` (C3); pressure: watch `7B0` |
| 8 | HV battery current sign | battery power (C.2) | hint: positive = discharge; confirm with regen in a drive log |
| 9 | HV battery type and cell blocks | battery column | **found**: lithium-ion, 56 cells (C5) |
| 10 | Formulas of values that answer | everything shown | compare against the car's display or a scan app's display: HV V/A, fuel litres, battery temps, `10A1` rpm |
| 11 | Inverter water pump speed / duty | HybridCrown Advanced screen and cooling alarms (role `inverterPump`) | speed **confirmed 2019**: `7D2 22 1039` bytes 2-3, ×1 rpm; HybridLogger logs hold 3432 and 3450 rpm parked in READY (session-20261114-180641), about 4250 rpm cold, 160 rpm before READY (HybridLogger docs/02 C13, profile 15). The OBDb signal on that request ("Front/rear motor torque ratio", for AWD) is disabled. Duty `7D2 22 1112` is refused (NRC 31); `1039` bytes 0-1 read as a duty with its formula (2.5 / 31 / 26 %) is a guess, *untested 2019*, not shown. Profile version 9 |
| 12 | Engine water pump speed | HybridCrown Advanced screen (roles `engineWaterPump`, `engineWaterPumpTarget`) | **confirmed 2019**: `700 22 1043` (target byte 0, actual byte 1, ×25 rpm) and `700 22 2827` (actual, ×40 rpm), from the OBDb Prius list; HybridLogger session-20261114-101039: 0 with the engine stopped, 1500-2775 rpm running, target 25-125 rpm above actual (HybridLogger docs/02 C8). Profile version 7 |
