package com.yellastrodev.yandexmusiclib.yUtils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

object IntOrStringAsStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IntOrStringAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("This serializer only works with JSON")

        val element = input.decodeJsonElement()
        if (element !is JsonPrimitive)
            throw IllegalArgumentException("Unexpected JSON element: $element")

        return element.intOrNull?.toString() ?: element.content
    }
}
