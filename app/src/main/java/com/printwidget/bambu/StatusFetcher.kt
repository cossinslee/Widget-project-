package com.printwidget.bambu

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

class AuthExpiredException : Exception("Your Bambu sign-in has expired. Open the app and sign in again.")

/**
 * Reads live status from Bambu's cloud MQTT broker. This is the same feed Bambu Handy uses:
 * we connect briefly, ask each printer for a full status report ("pushall"), and disconnect.
 */
class StatusFetcher(private val region: Region, private val userId: String, private val token: String) {

    fun fetch(printers: List<Printer>, timeoutSeconds: Long = 20): List<PrintStatus> {
        val now = System.currentTimeMillis()
        val online = printers.filter { it.online }
        val results = ConcurrentHashMap<String, PrintStatus>()
        printers.forEach {
            val initial = PrintStatus.offline(it, now)
            // Online printers that never answer show "no data" rather than a misleading "idle".
            results[it.serial] = if (it.online) initial.copy(state = "NO_DATA") else initial
        }
        if (online.isEmpty()) return printers.map { results.getValue(it.serial) }

        val gotFullReport = ConcurrentHashMap.newKeySet<String>()
        val latch = CountDownLatch(online.size)

        val client = MqttClient(
            "ssl://${region.mqttHost}:8883",
            "printwidget_" + UUID.randomUUID().toString().take(12),
            MemoryPersistence(),
        )
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {}
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                val serial = topic.removePrefix("device/").substringBefore('/')
                val previous = results[serial] ?: return
                val merged = PrintStatus.merge(previous, String(message.payload), System.currentTimeMillis()) ?: return
                results[serial] = merged
                // A full report always includes gcode_state; partial ones may not.
                val isFull = runCatching {
                    JSONObject(String(message.payload)).optJSONObject("print")?.has("gcode_state") == true
                }.getOrDefault(false)
                if (isFull && gotFullReport.add(serial)) latch.countDown()
            }
        })

        val options = MqttConnectOptions().apply {
            userName = "u_$userId"
            password = token.toCharArray()
            isCleanSession = true
            connectionTimeout = 15
            keepAliveInterval = 30
            isAutomaticReconnect = false
            socketFactory = SSLSocketFactory.getDefault()
        }

        try {
            client.connect(options)
        } catch (e: MqttException) {
            if (e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
            ) throw AuthExpiredException()
            throw e
        }

        try {
            for (p in online) {
                client.subscribe("device/${p.serial}/report", 0)
                val pushAll = JSONObject().put(
                    "pushing",
                    JSONObject().put("sequence_id", "0").put("command", "pushall")
                        .put("version", 1).put("push_target", 1),
                )
                client.publish("device/${p.serial}/request", MqttMessage(pushAll.toString().toByteArray()).apply { qos = 0 })
            }
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
        } finally {
            runCatching { client.disconnect(2_000) }
            runCatching { client.close() }
        }
        return printers.map { results.getValue(it.serial) }
    }
}
