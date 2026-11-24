package com.hybridguard.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Applies a profile's `overrides.json` to raw OBDb command objects, before parsing.
 *
 * ```json
 * {
 *   "note": "free text",
 *   "addCommands":    [ { ...an OBDb command... } ],
 *   "commands":       { "7D2:221F9A": { "freq": 0.25 } },
 *   "signals":        { "COROLLAHYBRID_HVBAT_C": { "fmt": { "mul": -1 } } },
 *   "disableSignals": [ "SOME_SIGNAL_ID" ]
 * }
 * ```
 *
 * Patches are shallow merges; a `null` value removes a field. `fmt` patches
 * merge into the existing `fmt`. Every key and id must match something,
 * so a typo fails loudly instead of silently doing nothing.
 */
internal object Overrides {

    private val ALLOWED_KEYS = setOf("note", "addCommands", "commands", "signals", "disableSignals")

    fun apply(commands: MutableList<JsonObject>, overrides: JsonObject, source: String) {
        val unknown = overrides.keys - ALLOWED_KEYS
        if (unknown.isNotEmpty()) throw ProfileFormatException("$source: unknown keys $unknown")

        overrides["addCommands"]?.jsonArray?.forEach { commands += it.jsonObject }

        overrides["commands"]?.jsonObject?.forEach { (key, patch) ->
            val upperKey = key.uppercase()
            var matched = false
            commands.replaceAll { command ->
                if (SignalSetParser.keyOf(command) == upperKey) {
                    matched = true
                    for (field in listOf("hdr", "cmd", "signals")) {
                        if (field in patch.jsonObject) throw ProfileFormatException("$source: command $key: cannot patch \"$field\"")
                    }
                    merge(command, patch.jsonObject)
                } else {
                    command
                }
            }
            if (!matched) throw ProfileFormatException("$source: no command $key to patch")
        }

        overrides["signals"]?.jsonObject?.forEach { (id, patch) ->
            var matched = false
            commands.replaceAll { command ->
                mapSignals(command) { signal ->
                    if (signal.string("id") != id) return@mapSignals signal
                    matched = true
                    val p = patch.jsonObject
                    if ("id" in p) throw ProfileFormatException("$source: signal $id: cannot patch \"id\"")
                    val fmt = p["fmt"]?.let { merge(signal["fmt"]!!.jsonObject, it.jsonObject) }
                    merge(signal, JsonObject(p - "fmt")).let { if (fmt != null) JsonObject(it + ("fmt" to fmt)) else it }
                }
            }
            if (!matched) throw ProfileFormatException("$source: no signal $id to patch")
        }

        overrides["disableSignals"]?.jsonArray?.forEach { element ->
            val id = element.jsonPrimitive.content
            var matched = false
            commands.replaceAll { command ->
                val signals = command["signals"]!!.jsonArray
                val kept = signals.filterNot { it.jsonObject.string("id") == id }
                if (kept.size != signals.size) matched = true
                JsonObject(command + ("signals" to JsonArray(kept)))
            }
            if (!matched) throw ProfileFormatException("$source: no signal $id to disable")
            commands.removeAll { it["signals"]!!.jsonArray.isEmpty() }
        }
    }

    private fun mapSignals(command: JsonObject, transform: (JsonObject) -> JsonObject): JsonObject {
        val signals = command["signals"]?.jsonArray ?: return command
        return JsonObject(command + ("signals" to JsonArray(signals.map { transform(it.jsonObject) })))
    }

    /** Shallow merge; JSON null in [patch] removes the field. */
    private fun merge(base: JsonObject, patch: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((k, v) in patch) if (v is JsonNull) out.remove(k) else out[k] = v
        return JsonObject(out)
    }
}
