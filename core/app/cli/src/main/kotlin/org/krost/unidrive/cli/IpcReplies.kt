package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The line as a JSON object, or null when it is not one. */
internal fun String.asJsonObjectOrNull(): JsonObject? = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** True only for a top-level `"ok":true` boolean; text that looks like it, in a string or a nested object, does not count. */
internal fun JsonObject.isOkReply(): Boolean = (this["ok"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
