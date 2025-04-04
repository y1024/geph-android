package io.geph.android

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class DaemonArgs(
    val secret: String,
    val metadata: JsonElement,

    @SerialName("app_whitelist")
    val appWhitelist: List<String>,

    @SerialName("prc_whitelist")
    val prcWhitelist: Boolean,

    val exit: JsonElement,


    @SerialName("listen_all")
    val listenAll: Boolean,

) {
    fun toConfig(ctx: Context): JsonElement {
        return buildJsonObject {
            for ((originalKey, originalValue) in configTemplate()) {
                put(originalKey, originalValue)
            }

            when (exit) {
                is JsonObject -> {
                    putJsonObject("exit_constraint") {
                        putJsonArray("country_city") {
                            add(exit.get("country")!!)
                            add(exit.get("city")!!)
                        }
                    }
                }
                else -> {}
            }

            put("sess_metadata", metadata)
            put("dry_run", false)
            put("passthrough_china", prcWhitelist)
            if (prcWhitelist || !(metadata is JsonNull)) {
                put("spoof_dns", true)
            }
            put("cache", ctx.filesDir.toString() + "/cache_" + secret)
            putJsonObject("credentials") {
                Log.e("SECRET", secret)
                put("secret", secret)
            }
        }
    }
}

fun configTemplate(): JsonObject {
    return buildJsonObject {
        put("exit_constraint", "auto")
        put("bridge_mode", "Auto")
        put("cache", JsonNull)

        putJsonObject("broker") {
            putJsonArray("race") {
                // 1) First fronted
                addJsonObject {
                    putJsonObject("fronted") {
                        put("front", "https://www.cdn77.com/")
                        put("host", "1826209743.rsc.cdn77.org")
                    }
                }
                // 2) Second fronted
                addJsonObject {
                    putJsonObject("fronted") {
                        put("front", "https://vuejs.org/")
                        put("host", "svitania-naidallszei-2.netlify.app")
                    }
                }
            }
        }

        putJsonObject("broker_keys") {
            put("master", "88c1d2d4197bed815b01a22cadfc6c35aa246dddb553682037a118aebfaa3954")
            put("mizaru_free", "0558216cbab7a9c46f298f4c26e171add9af87d0694988b8a8fe52ee932aa754")
            put("mizaru_plus", "cf6f58868c6d9459b3a63bc2bd86165631b3e916bad7f62b578cd9614e0bcb3b")
        }

        put("vpn", false)
        put("spoof_dns", false)
        put("passthrough_china", false)
        put("dry_run", true)

        putJsonObject("credentials") {
            put("secret", "")
        }

        put("sess_metadata", JsonNull)
        put("task_limit", JsonNull)
    }
}