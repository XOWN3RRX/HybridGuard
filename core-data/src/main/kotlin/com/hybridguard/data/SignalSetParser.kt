package com.hybridguard.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

class ProfileFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Parses OBDb signalset v3 JSON (`{"commands": [...]}`) into [SignalCommand]s. */
object SignalSetParser {

    private val SUPPORTED_SERVICES = setOf("01", "21", "22")

    fun parse(text: String, source: String): List<SignalCommand> = parseCommands(commandsOf(parseJson(text, source), source), source)

    internal fun parseJson(text: String, source: String): JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw ProfileFormatException("$source: not a JSON object (${e.message})", e)
    }

    internal fun commandsOf(root: JsonObject, source: String): JsonArray =
        (root["commands"] ?: throw ProfileFormatException("$source: missing \"commands\"")) as? JsonArray
            ?: throw ProfileFormatException("$source: \"commands\" is not an array")

    internal fun parseCommands(commands: JsonArray, source: String): List<SignalCommand> =
        commands.mapIndexed { i, element ->
            val where = "$source commands[$i]"
            try {
                parseCommand(element.jsonObject)
            } catch (e: ProfileFormatException) {
                throw ProfileFormatException("$where: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw ProfileFormatException("$where: ${e.message}", e)
            }
        }

    /** `"7D2:221F9A"` for a raw OBDb command object, used to address commands in overrides. */
    internal fun keyOf(command: JsonObject): String {
        val (service, parameter) = parameterOf(command)
        return commandKey(hex(command, "hdr"), "%02X".format(service) + parameter)
    }

    private fun parseCommand(o: JsonObject): SignalCommand {
        val header = hex(o, "hdr")
        if (o.string("hdr")!!.length != 3) throw ProfileFormatException("only 11-bit headers are supported, got ${o.string("hdr")}")
        val (service, parameter) = parameterOf(o)
        val freqSeconds = o.number("freq") ?: throw ProfileFormatException("missing \"freq\"")
        require(freqSeconds > 0) { "freq must be positive" }

        val signals = (o["signals"] as? JsonArray ?: throw ProfileFormatException("missing \"signals\""))
            .map { parseSignal(it.jsonObject) }

        return SignalCommand(
            header = header,
            receiveAddress = o.string("rax")?.toInt(16),
            service = service,
            parameterHex = parameter,
            periodMs = (freqSeconds * 1000).roundToLong().coerceAtLeast(1),
            signals = signals,
            forceFlowControl = o.bool("fcm1") ?: false,
            debug = o.bool("dbg") ?: false,
            filter = (o["filter"] as? JsonObject)?.let(::parseFilter),
            extendedAddress = o.string("eax")?.toInt(16),
            testerAddress = o.string("tst")?.toInt(16),
        )
    }

    /** `{"22": "1F9A"}` -> (0x22, "1F9A"). Service 22 identifiers are 4 hex digits, others 2. */
    private fun parameterOf(o: JsonObject): Pair<Int, String> {
        val cmd = o["cmd"] as? JsonObject ?: throw ProfileFormatException("missing \"cmd\"")
        val (serviceHex, value) = cmd.entries.singleOrNull()
            ?: throw ProfileFormatException("\"cmd\" must have exactly one service, got ${cmd.keys}")
        if (serviceHex !in SUPPORTED_SERVICES) throw ProfileFormatException("unsupported service \"$serviceHex\"")
        val primitive = value as? JsonPrimitive ?: throw ProfileFormatException("bad \"cmd\" value")
        val id = if (primitive.isString) primitive.content.toInt(16) else primitive.intOrNull
            ?: throw ProfileFormatException("bad \"cmd\" value ${primitive.content}")
        val width = if (serviceHex == "22") 4 else 2
        return serviceHex.toInt(16) to "%0${width}X".format(id)
    }

    private fun parseSignal(o: JsonObject): Signal {
        val id = o.string("id") ?: throw ProfileFormatException("signal without \"id\"")
        val fmt = o["fmt"] as? JsonObject ?: throw ProfileFormatException("signal $id: missing \"fmt\"")
        val format = try {
            parseFormat(fmt)
        } catch (e: Exception) {
            throw ProfileFormatException("signal $id: ${e.message}", e)
        }
        return Signal(
            id = id,
            name = o.string("name") ?: id,
            format = format,
            description = o.string("description"),
            path = o.string("path"),
            hidden = o.bool("hidden") ?: false,
            suggestedMetric = o.string("suggestedMetric"),
        )
    }

    private fun parseFormat(f: JsonObject): SignalFormat {
        val bitOffset = f.int("bix") ?: 0
        val bitLength = f.int("len") ?: throw ProfileFormatException("missing \"len\"")
        val map = f["map"] as? JsonObject
        if (map != null) {
            return Enumeration(
                bitOffset = bitOffset,
                bitLength = bitLength,
                map = map.entries.associate { (k, v) ->
                    val entry = when (v) {
                        is JsonObject -> EnumEntry(v.string("value") ?: k, v.string("description") ?: v.string("value") ?: k)
                        else -> v.jsonPrimitive.content.let { EnumEntry(it, it) }
                    }
                    (k.toLongOrNull() ?: throw ProfileFormatException("enum key \"$k\" is not a number")) to entry
                },
            )
        }
        return Scaling(
            bitOffset = bitOffset,
            bitLength = bitLength,
            max = f.number("max") ?: throw ProfileFormatException("missing \"max\""),
            unit = f.string("unit") ?: throw ProfileFormatException("missing \"unit\""),
            min = f.number("min") ?: 0.0,
            mul = f.number("mul") ?: 1.0,
            div = f.number("div") ?: 1.0,
            add = f.number("add") ?: 0.0,
            signed = f.bool("sign") ?: false,
            bytesLsb = f.bool("blsb") ?: false,
            nullMin = f.number("nullmin"),
            nullMax = f.number("nullmax"),
        )
    }

    private fun parseFilter(f: JsonObject) = CommandFilter(
        from = f.int("from"),
        to = f.int("to"),
        years = (f["years"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet()?.ifEmpty { null },
    )

    private fun hex(o: JsonObject, key: String): Int =
        o.string(key)?.toIntOrNull(16) ?: throw ProfileFormatException("missing or bad \"$key\"")
}

internal fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }
internal fun JsonObject.string(key: String): String? = primitive(key)?.content
internal fun JsonObject.number(key: String): Double? = primitive(key)?.doubleOrNull
internal fun JsonObject.int(key: String): Int? = primitive(key)?.let { it.intOrNull ?: it.longOrNull?.toInt() }
internal fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull
internal fun JsonElement.stringList(): List<String> = jsonArray.map { it.jsonPrimitive.content }
