package com.tirthankar.ascent.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Effects that decoded cleanly, plus the raw JSON of any that didn't. */
data class EffectDecodeResult(
    val effects: List<Effect>,
    val rejected: List<String>,
)

/**
 * JSON for [Effect]. Unknown kinds and invalid values are skipped and reported,
 * never thrown, so one bad item can't break reward calculation.
 */
object EffectCodec {

    private val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(effect: Effect): String = json.encodeToString(Effect.serializer(), effect)

    fun encodeList(effects: List<Effect>): String =
        json.encodeToString(
            JsonArray.serializer(),
            JsonArray(effects.map { json.encodeToJsonElement(Effect.serializer(), it) }),
        )

    /** One effect, or null if it is malformed, of an unknown kind, or has invalid values. */
    fun decode(text: String): Effect? =
        try {
            json.decodeFromString(Effect.serializer(), text)
        } catch (e: IllegalArgumentException) { // includes SerializationException
            null
        }

    /** A JSON array of effects. Bad elements are skipped; malformed input rejects everything. */
    fun decodeList(text: String): EffectDecodeResult {
        val array = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        } ?: return EffectDecodeResult(effects = emptyList(), rejected = listOf(text))

        val effects = mutableListOf<Effect>()
        val rejected = mutableListOf<String>()
        array.forEach { element ->
            val effect = decodeElement(element)
            if (effect != null) effects += effect else rejected += element.toString()
        }
        return EffectDecodeResult(effects, rejected)
    }

    private fun decodeElement(element: JsonElement): Effect? =
        try {
            json.decodeFromJsonElement(Effect.serializer(), element)
        } catch (e: IllegalArgumentException) {
            null
        }
}
