package org.krost.unidrive.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The plaintext shape inside the vault: profile name to credential field to value, as a JSON object of objects. */
internal object VaultJson {
    fun toJson(data: Map<String, Map<String, String>>): String {
        val obj =
            buildJsonObject {
                for ((profile, creds) in data) {
                    put(
                        profile,
                        buildJsonObject {
                            for ((k, v) in creds) put(k, JsonPrimitive(v))
                        },
                    )
                }
            }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    fun parseJson(json: String): Map<String, Map<String, String>> {
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj.mapValues { (_, v) ->
            v.jsonObject.mapValues { (_, field) -> field.jsonPrimitive.content }
        }
    }
}
