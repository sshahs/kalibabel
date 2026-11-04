package email.sshah.kalibabel.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

// Small, null-safe navigation helpers for the many provider JSON shapes we handle without DTOs.

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonElement?.bool(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull

fun JsonElement?.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

fun JsonElement?.double(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull

fun JsonElement?.at(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

val JsonElement?.asString: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>().orEmpty()

/** Pulls a human-readable message out of the error envelopes used by the providers we support. */
fun extractErrorMessage(body: String): String? {
    if (body.isBlank()) return null
    val el = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() ?: return body.take(300)
    val error = (el as? JsonObject)?.get("error")
    return error.str("message")
        ?: error.asString
        ?: el.str("message")
        ?: el.str("detail")
        ?: el.obj("detail").str("message")
        ?: el.arr("detail")?.firstOrNull().str("msg")
        ?: el.str("err_msg")
        ?: el.str("error_message")
        ?: body.take(300)
}
