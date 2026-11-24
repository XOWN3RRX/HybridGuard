# 03. Adapter and protocol

## Adapter

Any OBD-II adapter that is:

- **ELM327-compatible** (`AT` commands), with the CAN commands the app uses
  (`ATSH`, `ATCRA`, `ATFCSH`/`ATFCSD`/`ATFCSM`, `ATRV`). Adapters built on an
  STN chip (STN11xx / STN2xxx, also `ST` commands) are the safest choice;
  cheap "v1.5" / "v2.1" clones often lack some of them.
- **Bluetooth Classic (serial port profile)**. BLE-only adapters (most of
  the iPhone ones) are not supported yet. Dual-mode adapters often show a
  separate name for each mode: pair the classic (Android) one.
- **Low drain with automatic sleep**, because it stays plugged in. The app
  relies on the adapter falling asleep after the car is switched off (many
  do after 10-30 min, see the adapter's manual) and only sends `ATRV` until
  the car is READY. An adapter that never sleeps keeps draining the 12 V
  battery: unplug it when parking for long.

### Connection choice

**Use Bluetooth classic SPP first.** It is a plain byte stream, faster and
simpler than BLE.

- RFCOMM with the standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`
  on an already-paired device.
- Permissions: `BLUETOOTH_CONNECT` (and `BLUETOOTH_SCAN` only if the app does
  its own discovery) on Android 12+; legacy `BLUETOOTH` below that.
- BLE fallback only if classic fails: the GATT service/characteristic UUIDs
  for this adapter were **not found** in public documentation. Do not assume
  the common `FFF0/FFF1/FFF2` layout; read them from the device with nRF
  Connect first.

Android Auto (wired or wireless) and the adapter connection coexist: the phone
keeps the SPP link while projecting.

## ELM327 session

Line terminator `\r`. Every reply ends with the prompt `>`. Read until `>`.

Init sequence (log every reply):

```
ATZ            reset (wait up to 2 s)
ATE0           echo off
ATL0           linefeeds off
ATS0           spaces off
ATH1           show headers (needed to see which ECU replied)
ATAT1          adaptive timing
ATSP6          ISO 15765-4 CAN 11-bit 500k (do not rely on auto-detect)
ATI / STI / STDI / ATRV   identify adapter, read voltage
```

Per ECU, before sending its requests:

```
ATSH 7D2       transmit header
ATCRA 7DA      receive filter = header + 8
ATFCSH 7D2     flow-control header     \
ATFCSD 300000  flow-control data        > needed for multi-frame replies
ATFCSM1        flow-control mode 1     /
```

For functional service `01` requests: `ATSH 7DF`, `ATCRA` (clear filter) so
replies from both `7E8` and other ECUs are visible.

Performance notes:

- Changing header costs several round trips. **Group requests by ECU** in the
  schedule and switch header as rarely as possible.
- Append the expected number of response frames to service-01 requests
  (`010C1`) so the adapter returns without waiting for the timeout.
- Up to six service-01 PIDs can be combined in one CAN request (`01 0C 0D 05
  5B`), answered as one multi-frame reply. Test this; it roughly halves the
  round trips for the standard PIDs.
- Service `22` supports multiple DIDs per request on some ECUs. Test, do not
  assume.
- **Dashboard mode (app 0.5.0)**: a model of the link (60 ms per request,
  25 ms per AT command, from the 16.2 req/s measured below) showed the 0.4.0
  dashboard plan asking for 37 req/s and spending more link time on ECU
  switches than on requests: speed refreshed about every 1.9 s. Changes:
  each role polls only its first signal that answered in the probe; the
  detail values only while a design shows them; the scheduler picks the
  most overdue request in periods of its own, with an ECU switch counted as
  150 ms of waiting; a broadcast request that expects one single-frame
  reply leaves the custom flow control as it is, so `7DF` <-> `7D2` costs
  `ATSH` + `ATCRA` instead of five commands. Model result for the Big
  design: speed about every 0.5 s, battery power about every 1 s. **Still
  to be measured on the car.**
- Parallel requests are not possible: an ELM327-type adapter handles one
  request at a time. The remaining big gain is fewer round trips per value:
  multi-PID service `01` requests (below) and multi-DID `22` requests, both
  to be tested with HybridDash first.
- Once it works, try the STN speed-ups (`STPTO`, `STFAC`, higher baud is
  irrelevant over Bluetooth). Keep an ELM-only fallback path.

## Response parsing

With `ATH1` + `ATS0`, a single frame looks like:

```
7DA0562 1F5B 66 ...     ->  ID=7DA, PCI=05 (5 bytes), 62 1F5B = positive reply to 22 1F5B, data=66
```

Multi-frame (ISO-TP): first frame PCI `1x xx` carries total length; consecutive
frames `21`, `22`, ...; the ELM sends the flow control itself. Reassemble by
sequence number, trim to the declared length.

Handle explicitly:

- Positive reply = request service + `0x40` (`41`, `61`, `62`).
- Negative reply `7F <svc> <code>`: `11` service not supported, `12`
  sub-function not supported, `31` request out of range (DID does not exist),
  `78` response pending (keep waiting), `22` conditions not correct.
- Text errors: `NO DATA`, `CAN ERROR`, `BUFFER FULL`, `STOPPED`, `?`,
  `UNABLE TO CONNECT`, `BUS INIT`. Log them verbatim.
- 7C4/7C0 may need the ignition fully ON (READY) to answer.

## Safety

Allow-list of services in the transport layer: `01`, `09`, `21`, `22`, `3E`.
Anything else throws. No session control, no writes, no actuator tests, no DTC
clearing. Discovery scans only while parked.

## Findings on the owner's car (2026-11-13)

- Adapter: STN-based (`ATI` `ELM327 v2.2`, `STI` `STN1151 v4.3.2`), over
  Bluetooth Classic SPP. The whole init sequence above
  answers `OK` (after `ATZ` echo is still on, so the `ATE0` reply includes
  the echo).
- **The engine ECU does not answer on its own address `7E0`** (VIN, PID
  bitmaps, every `01` PID: `NO DATA`), but answers the broadcast `7DF` as
  `7E8`. `7DF 01 00` gets replies from `7E8`, `7EA`, `7EB` and `7EE`. Other
  ECUs (`7D2`, `747`, `7C0`, `7C4`, `7B0`) answer on their own addresses with
  the per-ECU setup above. Cause not known (gateway, or the ECU's physical
  address differs); not needed to work around it.
- The app therefore detects this at session start and sends standard
  requests (services `01`, `09`) to `7DF` with receive filter `ATCRA 7E8`
  and automatic flow control. The detection is generic: a car whose engine
  ECU answers `7E0` is unaffected.
- Request rate in the first session: 4.8 req/s, mostly lost to `NO DATA`
  timeouts. Now: requests that got no reply in the probe are retried every
  30 s instead of every cycle, and requests the probe saw answer in a single
  frame carry the response count (`221F5B1`). Multi-frame replies get no
  count. Measured on the car with app 0.1.2: **16.2 req/s** steady over
  6.5 minutes (standard PIDs through `7DF` only; 42 of 43 probed replies
  were single-frame). Still to measure with the full Corolla profile.
- The "bt socket closed" disconnect at the end of the first two car logs
  was not the car or the adapter: the owner had pressed Stop, which closed
  the socket under a pending read, and the app logged it as a dropout
  (fixed in 0.1.3). What the adapter does when the car is switched off
  with a session running is still untested.

## Testing without the car

- A fake transport that answers from a recorded log (first real log becomes
  the fixture).
- Optional: an ELM327 emulator on the PC (for example the Python
  `ELM327-emulator` project) over TCP, with a TCP transport in debug builds.
