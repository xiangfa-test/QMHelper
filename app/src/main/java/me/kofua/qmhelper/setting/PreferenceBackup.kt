package me.kofua.qmhelper.setting

import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.InputStream
import java.io.OutputStream

/** Reads and writes the Android preferences XML format independently of the storage backend. */
internal object PreferenceBackup {
    fun export(prefs: SharedPreferences, output: OutputStream) {
        val xml = Xml.newSerializer()
        xml.setOutput(output, "UTF-8")
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "map")
        for ((key, value) in prefs.all.toSortedMap()) {
            val tag = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is String -> "string"
                is Set<*> -> "set"
                else -> error("Unsupported preference type for $key")
            }
            xml.startTag(null, tag).attribute(null, "name", key)
            when (value) {
                is Set<*> -> value.forEach { xml.writeString(it as String) }
                is String -> xml.text(value)
                else -> xml.attribute(null, "value", value.toString())
            }
            xml.endTag(null, tag)
        }
        xml.endTag(null, "map")
        xml.endDocument()
    }

    fun restore(prefs: SharedPreferences, input: InputStream) {
        val values = read(input)
        val editor = prefs.edit()
        for (key in prefs.all.keys - values.keys) editor.remove(key)
        for ((key, value) in values) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
            }
        }
        check(editor.commit()) { "Preferences could not be saved" }
    }

    private fun read(input: InputStream): Map<String, Any> {
        val xml = Xml.newPullParser()
        xml.setInput(input, null)
        xml.nextTag()
        xml.require(XmlPullParser.START_TAG, null, "map")
        val values = linkedMapOf<String, Any>()
        while (xml.nextTag() == XmlPullParser.START_TAG) {
            val key = checkNotNull(xml.getAttributeValue(null, "name"))
            check(key !in values) { "Duplicate preference key" }
            val tag = xml.name
            values[key] = when (tag) {
                "string" -> xml.nextText()
                "set" -> buildSet {
                    while (xml.nextTag() == XmlPullParser.START_TAG) {
                        xml.require(XmlPullParser.START_TAG, null, "string")
                        add(xml.nextText())
                    }
                }
                else -> {
                    val value = checkNotNull(xml.getAttributeValue(null, "value"))
                    val parsed = when (tag) {
                        "boolean" -> value.toBooleanStrict()
                        "int" -> value.toInt()
                        "long" -> value.toLong()
                        "float" -> value.toFloat()
                        else -> error("Unsupported preference type")
                    }
                    xml.nextTag()
                    parsed
                }
            }
            xml.require(XmlPullParser.END_TAG, null, tag)
        }
        xml.require(XmlPullParser.END_TAG, null, "map")
        check(xml.next() == XmlPullParser.END_DOCUMENT)
        return values
    }

    private fun XmlSerializer.writeString(value: String) {
        startTag(null, "string").text(value).endTag(null, "string")
    }
}
