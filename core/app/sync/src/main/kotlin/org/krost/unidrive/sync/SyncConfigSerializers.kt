package org.krost.unidrive.sync

import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Decodes a TOML value that may be written as a single string or an array of strings. */
internal object StringOrListSerializer : KSerializer<List<String>> {
    private val list = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = list.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<String> =
        try {
            list.deserialize(decoder)
        } catch (_: TomlDecodingException) {
            listOf(decoder.decodeString())
        }
}

/** Decodes a TOML boolean that may also be written as the string "true" or "false", the way `profile add` writes its options. */
internal object BooleanOrStringSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = Boolean.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean =
        try {
            decoder.decodeBoolean()
        } catch (_: TomlDecodingException) {
            when (val s = decoder.decodeString()) {
                "true" -> true
                "false" -> false
                else -> throw SerializationException("expected true or false, got '$s'")
            }
        }
}

/**
 * `show_backups` as written. The key being absent is not a value here: that stays off, and is not [AUTO].
 * [ON] and [OFF] are the booleans `true` and `false`.
 */
enum class ShowBackups { AUTO, ON, OFF }

/** Decodes `show_backups`: `true`, `false`, or the string `auto` (a quoted `true` or `false` reads the same). */
internal object ShowBackupsSerializer : KSerializer<ShowBackups> {
    override val descriptor: SerialDescriptor = Boolean.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: ShowBackups,
    ) {
        when (value) {
            ShowBackups.ON -> encoder.encodeBoolean(true)
            ShowBackups.OFF -> encoder.encodeBoolean(false)
            ShowBackups.AUTO -> encoder.encodeString("auto")
        }
    }

    override fun deserialize(decoder: Decoder): ShowBackups =
        try {
            if (decoder.decodeBoolean()) ShowBackups.ON else ShowBackups.OFF
        } catch (_: TomlDecodingException) {
            when (val raw = decoder.decodeString().trim().lowercase()) {
                "auto" -> ShowBackups.AUTO
                "true" -> ShowBackups.ON
                "false" -> ShowBackups.OFF
                else -> throw SerializationException("show_backups must be auto, true or false, got '$raw'")
            }
        }
}

