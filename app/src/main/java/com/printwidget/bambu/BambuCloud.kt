package com.printwidget.bambu

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Which Bambu Lab cloud the account lives in. */
enum class Region(val apiHost: String, val webHost: String, val mqttHost: String) {
    GLOBAL("api.bambulab.com", "bambulab.com", "us.mqtt.bambulab.com"),
    CHINA("api.bambulab.cn", "bambulab.cn", "cn.mqtt.bambulab.com"),
}

data class Printer(val serial: String, val name: String, val model: String, val online: Boolean)

/** Result of a sign-in attempt. */
sealed class LoginResult {
    data class Success(val token: String) : LoginResult()
    /** Bambu e-mailed a 6-digit code; call [BambuCloud.loginWithEmailCode]. */
    object NeedsEmailCode : LoginResult()
    /** Two-factor auth is on; call [BambuCloud.loginWithTfa] with this key. */
    data class NeedsTfa(val tfaKey: String) : LoginResult()
}

class BambuApiException(message: String, val httpCode: Int = 0) : IOException(message)

/**
 * Minimal client for the same Bambu Lab cloud API that Bambu Handy and Bambu Studio use.
 * All calls block, so run them off the main thread.
 */
class BambuCloud(private val region: Region) {

    fun login(email: String, password: String): LoginResult {
        val body = JSONObject().put("account", email).put("password", password).put("apiError", "")
        val res = post("https://${region.apiHost}/v1/user-service/user/login", body)
        val token = res.optString("accessToken")
        if (token.isNotEmpty()) return LoginResult.Success(token)
        return when (res.optString("loginType")) {
            "verifyCode" -> {
                sendEmailCode(email)
                LoginResult.NeedsEmailCode
            }
            "tfa" -> LoginResult.NeedsTfa(res.optString("tfaKey"))
            else -> throw BambuApiException(errorText(res, "Sign-in failed. Check your e-mail and password."))
        }
    }

    fun sendEmailCode(email: String) {
        post(
            "https://${region.apiHost}/v1/user-service/user/sendemail/code",
            JSONObject().put("email", email).put("type", "codeLogin"),
        )
    }

    fun loginWithEmailCode(email: String, code: String): String {
        val res = post(
            "https://${region.apiHost}/v1/user-service/user/login",
            JSONObject().put("account", email).put("code", code.trim()),
        )
        return res.optString("accessToken").ifEmpty {
            throw BambuApiException(errorText(res, "That code didn't work. Request a new one and try again."))
        }
    }

    fun loginWithTfa(tfaKey: String, code: String): String {
        val conn = open("https://${region.webHost}/api/sign-in/tfa", "POST")
        conn.outputStream.use {
            it.write(JSONObject().put("tfaKey", tfaKey).put("tfaCode", code.trim()).toString().toByteArray())
        }
        val status = conn.responseCode
        // The token comes back as a cookie on this endpoint.
        val cookies = conn.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        val token = cookies.firstNotNullOfOrNull { cookie ->
            cookie.split(';').first().trim().takeIf { it.startsWith("token=") }?.removePrefix("token=")
        }
        val text = readBody(conn, status)
        if (!token.isNullOrEmpty()) return token
        val json = runCatching { JSONObject(text) }.getOrNull()
        json?.optString("accessToken")?.takeIf { it.isNotEmpty() }?.let { return it }
        throw BambuApiException("That two-factor code didn't work.", status)
    }

    /** The numeric user id, needed for the MQTT username. */
    fun fetchUserId(token: String): String {
        val res = get("https://${region.apiHost}/v1/design-user-service/my/preference", token)
        val uid = res.opt("uid")?.toString().orEmpty()
        if (uid.isEmpty() || uid == "null") throw BambuApiException("Couldn't read your Bambu account id.")
        return uid
    }

    fun fetchPrinters(token: String): List<Printer> {
        val res = get("https://${region.apiHost}/v1/iot-service/api/user/bind", token)
        val devices = res.optJSONArray("devices") ?: JSONArray()
        return (0 until devices.length()).map { i ->
            val d = devices.getJSONObject(i)
            Printer(
                serial = d.optString("dev_id"),
                name = d.optString("name").ifEmpty { d.optString("dev_id") },
                model = d.optString("dev_product_name"),
                online = d.optBoolean("online"),
            )
        }.filter { it.serial.isNotEmpty() }
    }

    // ---- HTTP plumbing ----

    private fun post(url: String, body: JSONObject, token: String? = null): JSONObject {
        val conn = open(url, "POST", token)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return parse(conn)
    }

    private fun get(url: String, token: String): JSONObject = parse(open(url, "GET", token))

    private fun open(url: String, method: String, token: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-BBL-Language", "en-US")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (method == "POST") conn.doOutput = true
        return conn
    }

    private fun parse(conn: HttpURLConnection): JSONObject {
        val status = conn.responseCode
        val text = readBody(conn, status)
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (status == 401 || status == 403) {
            throw BambuApiException(errorText(json, "Your Bambu sign-in has expired. Sign in again."), status)
        }
        if (status !in 200..299) {
            throw BambuApiException(errorText(json, "Bambu cloud returned an error (HTTP $status)."), status)
        }
        return json
    }

    private fun readBody(conn: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun errorText(json: JSONObject, fallback: String): String =
        json.optString("error").ifEmpty { json.optString("message") }.ifEmpty { fallback }
}
