package org.krost.unidrive.io

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The member [key] of this object when it is a JSON string; null for a missing member and for every other type (a number, a
 * boolean, null, an object, an array). The strict reading of a request or reply field that must be text.
 */
public fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Whether the member [key] is the JSON boolean `true`: a missing member, `false`, the string `"true"`, a number and null are all
 * false. The strict reading of a flag, so a client that writes `"true"` does not switch on what only a real `true` may.
 */
public fun JsonObject.isTrue(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
