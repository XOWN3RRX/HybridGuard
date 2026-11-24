package com.hybridguard.data

/**
 * A tiny made-up vehicle family for tests: a generic base profile and a
 * "test-ev" profile that includes it. Nothing here describes a real car.
 */
object FakeProfiles {

    val files: Map<String, String> = mapOf(
        "index.json" to """{ "profiles": ["generic", "test-ev"], "fallback": "generic" }""",

        "generic/profile.json" to """
            { "id": "generic", "name": "Generic", "version": 1,
              "match": { "vinPrefixes": [], "manual": true }, "includes": [] }
        """,
        "generic/signals.json" to """
            { "commands": [
              { "hdr": "7E0", "rax": "7E8", "cmd": {"01": "0D"}, "freq": 0.25,
                "signals": [ {"id": "VSS", "fmt": {"len": 8, "max": 255, "unit": "kilometersPerHour"}, "name": "Speed"} ] },
              { "hdr": "7E0", "rax": "7E8", "cmd": {"01": "0C"}, "freq": 0.25,
                "signals": [ {"id": "RPM", "fmt": {"len": 16, "max": 16383.75, "div": 4, "unit": "rpm"}, "name": "RPM"} ] }
            ] }
        """,
        "generic/derived.json" to """{ "speed": ["VSS"], "engineRpm": ["RPM"] }""",

        "test-ev/profile.json" to """
            { "id": "test-ev", "name": "Test EV", "version": 3,
              "match": { "vinPrefixes": ["TST1", "TST1AB", { "prefix": "tst2eu", "modelYear": 2021 }], "manual": true },
              "includes": ["generic"], "ecus": { "bms": "7E4" } }
        """,
        "test-ev/signals.json" to """
            { "commands": [
              { "hdr": "7E4", "rax": "7EC", "fcm1": true, "cmd": {"22": "0101"}, "freq": 1,
                "signals": [
                  {"id": "TEST_HV_V", "fmt": {"len": 16, "max": 1000, "div": 10, "unit": "volts"}, "name": "Pack voltage"},
                  {"id": "TEST_HV_A", "fmt": {"bix": 16, "len": 16, "min": -1000, "max": 1000, "div": 10, "sign": true, "unit": "amps"}, "name": "Pack current"}
                ] },
              { "hdr": "7E4", "rax": "7EC", "cmd": {"22": "0102"}, "freq": 5,
                "signals": [ {"id": "TEST_SOC", "fmt": {"len": 8, "max": 100, "mul": 100, "div": 255, "unit": "percent"}, "name": "SOC"} ] },
              { "hdr": "7E4", "rax": "7EC", "cmd": {"22": "0103"}, "freq": 5, "filter": {"from": 2030},
                "signals": [ {"id": "TEST_FUTURE", "fmt": {"len": 8, "max": 255, "unit": "scalar"}, "name": "Only on 2030+ cars"} ] },
              { "hdr": "7E4", "rax": "7EC", "cmd": {"22": "0104"}, "freq": 5,
                "signals": [ {"id": "TEST_UNUSED", "fmt": {"len": 8, "max": 255, "unit": "scalar"}, "name": "Disabled by override"} ] }
            ] }
        """,
        // A "correction found on the car": the current sign is inverted, and SOC is polled faster.
        "test-ev/overrides.json" to """
            { "note": "test corrections",
              "signals": { "TEST_HV_A": { "fmt": { "mul": -1 } } },
              "commands": { "7E4:220102": { "freq": 1 } },
              "disableSignals": ["TEST_UNUSED"] }
        """,
        "test-ev/derived.json" to """
            { "hvVoltage": ["TEST_HV_V"], "hvCurrent": ["TEST_HV_A"], "soc": ["TEST_SOC"], "gear": [] }
        """,
    )

    fun source(extra: Map<String, String> = emptyMap()) = MapSource(files + extra)
}
