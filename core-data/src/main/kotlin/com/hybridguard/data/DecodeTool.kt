package com.hybridguard.data

import java.io.File
import java.io.Writer
import kotlin.system.exitProcess

/**
 * Offline log decoder (docs/05 Phase 1 acceptance):
 *
 *     ./gradlew :core-data:run --args="decode session.jsonl.gz [out.csv]"
 *     ./gradlew :core-data:run --args="discovery session.jsonl.gz [more logs ...]"   (see DiscoveryAnalysis)
 *
 * Decodes every sample with the bundled profile named in the log's metadata
 * (so corrected formulas apply to old logs) and writes long-format CSV:
 * one row per signal value, plus one row per failed request.
 */
fun main(args: Array<String>) {
    if (args.size >= 2 && args[0] == "discovery") {
        val files = args.drop(1).map(::File)
        files.firstOrNull { !it.isFile }?.let {
            System.err.println("no such file: ${it.absolutePath}")
            exitProcess(1)
        }
        print(DiscoveryAnalysis.report(files, ProfileLoader()))
        return
    }
    if (args.size !in 2..3 || args[0] != "decode") {
        System.err.println("usage: decode <session.jsonl[.gz]> [out.csv]")
        System.err.println("       discovery <session.jsonl[.gz]> [more logs ...]")
        exitProcess(2)
    }
    val input = File(args[1])
    if (!input.isFile) {
        System.err.println("no such file: ${input.absolutePath}")
        exitProcess(1)
    }
    val output = args.getOrNull(2)?.let(::File)
        ?: File(input.parentFile, input.name.removeSuffix(".gz").removeSuffix(".jsonl") + ".csv")

    val stats = output.bufferedWriter().use { DecodeTool.decodeToCsv(input, it, ProfileLoader()) }
    println("${stats.samples} samples, ${stats.rows} rows -> ${output.absolutePath}")
    if (stats.unknownRequests > 0) println("${stats.unknownRequests} samples were not in the profile catalog (raw only)")
}

object DecodeTool {
    data class Stats(val samples: Int, val rows: Int, val unknownRequests: Int)

    const val HEADER = "t_ms,ecu,request,status,signal_id,signal_name,value,unit,raw_response"

    fun decodeToCsv(input: File, out: Writer, loader: ProfileLoader): Stats {
        var decoder: Decoder? = null
        var catalog: VehicleCatalog? = null
        var samples = 0
        var rows = 0
        var unknown = 0
        out.write(HEADER + "\n")

        SessionLogReader.read(input) { entry ->
            when (entry) {
                is LogEntry.Vehicle -> {
                    val c = loader.load(entry.info.profileId).forModelYear(entry.info.modelYear)
                    catalog = c
                    decoder = Decoder(c)
                }
                is LogEntry.Sample -> {
                    samples++
                    val s = entry.sample
                    val prefix = listOf(s.tMs.toString(), "%03X".format(s.header), s.request)
                    val decoded = decoder?.decode(s)
                    if (decoded == null) unknown++
                    if (decoded == null || decoded.values.isEmpty()) {
                        val status = decoded?.status ?: s.status
                        out.write(csv(prefix + listOf(LogFormat.statusName(status), "", "", "", "", s.response)))
                        rows++
                    } else {
                        for ((id, value) in decoded.values) {
                            val (text, unit) = when (value) {
                                is SignalValue.Number -> value.value.toString() to value.unit
                                is SignalValue.Enumerated -> value.value to ""
                                SignalValue.NoValue -> "" to ""
                                is SignalValue.Missing -> "" to ""
                            }
                            val name = catalog?.signal(id)?.name ?: ""
                            out.write(csv(prefix + listOf("ok", id, name, text, unit, s.response)))
                            rows++
                        }
                    }
                }
                else -> Unit
            }
        }
        return Stats(samples, rows, unknown)
    }

    private fun csv(fields: List<String>): String =
        fields.joinToString(",") { f ->
            if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + f.replace("\"", "\"\"") + "\"" else f
        } + "\n"
}
