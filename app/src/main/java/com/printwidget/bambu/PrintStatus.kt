package com.printwidget.bambu

import org.json.JSONObject

/** Snapshot of one printer, as shown on the widget. */
data class PrintStatus(
    val serial: String,
    val printerName: String,
    /** Bambu's gcode_state: IDLE, PREPARE, RUNNING, PAUSE, FINISH, FAILED, SLICING,
     *  plus OFFLINE and NO_DATA (online but didn't answer) from this app. */
    val state: String,
    val jobName: String,
    val percent: Int,
    val remainingMinutes: Int,
    val layer: Int,
    val totalLayers: Int,
    /** When this snapshot was taken (epoch ms). */
    val fetchedAt: Long,
) {
    val isActive: Boolean get() = state in ACTIVE_STATES

    /** Estimated finish time (epoch ms), counted from when the snapshot was taken. */
    val finishAt: Long get() = fetchedAt + remainingMinutes * 60_000L

    fun toJson(): JSONObject = JSONObject()
        .put("serial", serial).put("printerName", printerName).put("state", state)
        .put("jobName", jobName).put("percent", percent).put("remainingMinutes", remainingMinutes)
        .put("layer", layer).put("totalLayers", totalLayers).put("fetchedAt", fetchedAt)

    companion object {
        val ACTIVE_STATES = setOf("RUNNING", "PREPARE", "PAUSE", "SLICING")

        fun fromJson(o: JSONObject) = PrintStatus(
            serial = o.optString("serial"),
            printerName = o.optString("printerName"),
            state = o.optString("state", "IDLE"),
            jobName = o.optString("jobName"),
            percent = o.optInt("percent"),
            remainingMinutes = o.optInt("remainingMinutes"),
            layer = o.optInt("layer"),
            totalLayers = o.optInt("totalLayers"),
            fetchedAt = o.optLong("fetchedAt"),
        )

        fun offline(printer: Printer, now: Long) =
            PrintStatus(printer.serial, printer.name, "OFFLINE", "", 0, 0, 0, 0, now)

        /**
         * Merges one MQTT "device/<serial>/report" message into [previous].
         * The printer often sends partial updates, so fields that are missing keep their old value.
         * Returns null if the message has no print data.
         */
        fun merge(previous: PrintStatus, payload: String, now: Long): PrintStatus? {
            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val p = root.optJSONObject("print") ?: return null
            if (!p.has("gcode_state") && !p.has("mc_remaining_time") && !p.has("mc_percent")) return null
            fun int(key: String, old: Int) = if (p.has(key)) p.optInt(key, old) else old
            val job = p.optString("subtask_name").ifEmpty { p.optString("gcode_file") }
                .ifEmpty { previous.jobName }
                .removeSuffix(".gcode").removeSuffix(".3mf")
            return previous.copy(
                state = p.optString("gcode_state").ifEmpty { previous.state.takeUnless { it == "OFFLINE" || it == "NO_DATA" } ?: "IDLE" },
                jobName = job,
                percent = int("mc_percent", previous.percent),
                remainingMinutes = int("mc_remaining_time", previous.remainingMinutes),
                layer = int("layer_num", previous.layer),
                totalLayers = int("total_layer_num", previous.totalLayers),
                fetchedAt = now,
            )
        }
    }
}
