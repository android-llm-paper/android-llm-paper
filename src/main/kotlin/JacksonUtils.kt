package moe.reimu

import com.fasterxml.jackson.core.JsonGenerator

fun JsonGenerator.newObject(callback: () -> Unit) {
    writeStartObject()
    callback()
    writeEndObject()
}

fun JsonGenerator.newObjectField(name: String, callback: () -> Unit) {
    writeObjectFieldStart(name)
    callback()
    writeEndObject()
}

fun JsonGenerator.newArrayField(name: String, callback: () -> Unit) {
    writeArrayFieldStart(name)
    callback()
    writeEndArray()
}