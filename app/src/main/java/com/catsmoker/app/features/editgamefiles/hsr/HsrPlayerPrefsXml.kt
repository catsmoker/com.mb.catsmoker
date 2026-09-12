package com.catsmoker.app.features.editgamefiles.hsr

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.io.StringWriter

/**
 * Parse and re-serialize Unity's `playerprefs.xml` (`<map>` of typed `<int>`/`<string>`/…
 * entries) without losing the entries this editor does not understand.
 *
 * Cross-checked against `UnityXmlHandler` inside
 * `referance/gamingtools/hsrgraphicdroid-main/utils/HsrGameManager.kt`
 * (`com.ireddragonicy.hsrgraphicdroid.utils.HsrGameManager`): same pull-parser treatment of
 * `int` and `string`, same serializer output shape, same `standalone` fix-up. One deliberate
 * divergence, recorded here rather than silently copied: the reference keeps only `int` and
 * `string` entries, so a round-trip through it *deletes* any `long`/`float`/`boolean` entry
 * the game wrote. Unity does use those types in playerprefs files; deleting entries the
 * editor never looked at is exactly the "restore an assumed value" mistake this codebase
 * refuses elsewhere. This handler round-trips all five entry types, preserving document
 * order via [LinkedHashMap], and only ever changes the handful of keys
 * [HsrGameManager] writes.
 *
 * Kept free of any Android shell or Context dependency so it can be unit-tested like the
 * four parsers under the per-feature `engine` packages.
 */
object HsrPlayerPrefsXml {

    /** A typed playerprefs value. The tag name is kept so serialization is lossless. */
    sealed class Value {
        abstract fun serialized(): String

        data class IntValue(val value: Int) : Value() {
            override fun serialized() = value.toString()
        }

        data class LongValue(val value: Long) : Value() {
            override fun serialized() = value.toString()
        }

        data class FloatValue(val value: Float) : Value() {
            override fun serialized() = value.toString()
        }

        data class BooleanValue(val value: Boolean) : Value() {
            override fun serialized() = value.toString()
        }

        data class StringValue(val value: String) : Value() {
            override fun serialized() = value
        }
    }

    fun parse(xml: String): MutableMap<String, Value> {
        val map = linkedMapOf<String, Value>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(null, "name")
                    if (name != null) {
                        when (parser.name) {
                            "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()
                                ?.let { map[name] = Value.IntValue(it) }
                            "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()
                                ?.let { map[name] = Value.LongValue(it) }
                            "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()
                                ?.let { map[name] = Value.FloatValue(it) }
                            "boolean" -> parser.getAttributeValue(null, "value")?.toBoolean()
                                ?.let { map[name] = Value.BooleanValue(it) }
                            "string" -> map[name] = Value.StringValue(parser.nextText())
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // A file that does not parse produces an empty map, which the manager reports as a
            // parse failure rather than an empty settings object.
            return linkedMapOf()
        }
        return map
    }

    fun serialize(map: Map<String, Value>): String {
        val output = StringWriter()
        val serializer = Xml.newSerializer()
        serializer.setOutput(output)
        serializer.startDocument("utf-8", true)
        serializer.text("\n")
        serializer.startTag(null, "map")
        serializer.text("\n")
        for ((key, value) in map) {
            serializer.text("    ")
            when (value) {
                is Value.StringValue -> {
                    serializer.startTag(null, "string")
                    serializer.attribute(null, "name", key)
                    serializer.text(value.serialized())
                    serializer.endTag(null, "string")
                }
                else -> {
                    val tag = when (value) {
                        is Value.IntValue -> "int"
                        is Value.LongValue -> "long"
                        is Value.FloatValue -> "float"
                        is Value.BooleanValue -> "boolean"
                        else -> "string"
                    }
                    serializer.startTag(null, tag)
                    serializer.attribute(null, "name", key)
                    serializer.attribute(null, "value", value.serialized())
                    serializer.endTag(null, tag)
                }
            }
            serializer.text("\n")
        }
        serializer.endTag(null, "map")
        serializer.endDocument()
        // XmlSerializer writes standalone='true'; Unity's own file says standalone="yes".
        // Same normalization the reference performs.
        return output.toString().replace("standalone='true'", "standalone=\"yes\"")
    }
}
