package com.printwidget.bambu

import android.content.Context
import org.json.JSONArray

/**
 * App-private settings and the last known printer status.
 * Stored in the app's private storage, which other apps can't read.
 * Your Bambu password is never saved, only the sign-in token.
 */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("print_widget", Context.MODE_PRIVATE)

    var region: Region
        get() = runCatching { Region.valueOf(prefs.getString("region", null) ?: "") }.getOrDefault(Region.GLOBAL)
        set(v) = prefs.edit().putString("region", v.name).apply()

    var email: String
        get() = prefs.getString("email", "").orEmpty()
        set(v) = prefs.edit().putString("email", v).apply()

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(v) = prefs.edit().putString("token", v).apply()

    var userId: String
        get() = prefs.getString("uid", "").orEmpty()
        set(v) = prefs.edit().putString("uid", v).apply()

    /** Serial of the printer to show, or empty for "whichever is printing". */
    var selectedSerial: String
        get() = prefs.getString("selected", "").orEmpty()
        set(v) = prefs.edit().putString("selected", v).apply()

    /** Shown on the widget when the last refresh failed. */
    var lastError: String
        get() = prefs.getString("error", "").orEmpty()
        set(v) = prefs.edit().putString("error", v).apply()

    var printers: List<Printer>
        get() {
            val arr = runCatching { JSONArray(prefs.getString("printers", "[]")) }.getOrElse { JSONArray() }
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Printer(o.optString("serial"), o.optString("name"), o.optString("model"), o.optBoolean("online"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    org.json.JSONObject().put("serial", it.serial).put("name", it.name)
                        .put("model", it.model).put("online", it.online),
                )
            }
            prefs.edit().putString("printers", arr.toString()).apply()
        }

    var statuses: List<PrintStatus>
        get() {
            val arr = runCatching { JSONArray(prefs.getString("statuses", "[]")) }.getOrElse { JSONArray() }
            return (0 until arr.length()).map { PrintStatus.fromJson(arr.getJSONObject(it)) }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            prefs.edit().putString("statuses", arr.toString()).apply()
        }

    val isSignedIn: Boolean get() = token.isNotEmpty() && userId.isNotEmpty()

    fun signOut() {
        prefs.edit().remove("token").remove("uid").remove("printers").remove("statuses")
            .remove("selected").remove("error").apply()
    }

    /** The status the widget should show, following the user's printer choice. */
    fun statusToShow(): PrintStatus? = pickStatus(statuses, selectedSerial)

    companion object {
        fun pickStatus(all: List<PrintStatus>, selectedSerial: String): PrintStatus? {
            if (selectedSerial.isNotEmpty()) all.firstOrNull { it.serial == selectedSerial }?.let { return it }
            // Auto: the active print finishing soonest, otherwise the first online printer.
            return all.filter { it.isActive }.minByOrNull { it.remainingMinutes }
                ?: all.firstOrNull { it.state != "OFFLINE" }
                ?: all.firstOrNull()
        }
    }
}
