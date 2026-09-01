package one.epicsoft.gradle.tolgee.api

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.TreeMap

/**
 * Reading and writing the flat translation JSON.
 */
object TranslationJson {

    // disableHtmlEscaping: without it Gson turns <, >, &, = and ' into < and
    // friends. Translations contain all of those, and the escaped file is both
    // unreadable in review and needlessly different from what Tolgee returned.
    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * Parses an export response into a sorted key/value map.
     *
     * Sorting is not cosmetic: the export order is the server's business and can
     * change between calls, which would produce a diff of the whole file for a
     * single changed string.
     *
     * @throws IllegalArgumentException when the payload is not a flat JSON object.
     */
    fun parse(body: String): Map<String, String?> {
        val root = runCatching { JsonParser.parseString(body) }
            .getOrElse { throw IllegalArgumentException("response is not valid JSON") }
        if (!root.isJsonObject) {
            throw IllegalArgumentException("response is not a JSON object")
        }
        val result = TreeMap<String, String?>()
        for ((key, value) in root.asJsonObject.entrySet()) {
            when {
                value.isJsonNull -> result[key] = null
                value.isJsonPrimitive -> result[key] = value.asString
                // A nested object means the export was not flat — see ExportUrl.
                else -> throw IllegalArgumentException(
                    "key '$key' holds a nested structure; the export is not flat. " +
                        "The request must send an empty 'structureDelimiter' parameter."
                )
            }
        }
        return result
    }

    /** Serializes a map back to pretty printed JSON with a trailing newline. */
    fun write(translations: Map<String, String?>): String {
        val json = JsonObject()
        translations.forEach { (key, value) -> json.addProperty(key, value) }
        return GSON.toJson(json) + "\n"
    }
}
