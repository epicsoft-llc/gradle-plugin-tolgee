package one.epicsoft.gradle.tolgee.api

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds the Tolgee export URL. Kept separate from the task so it can be tested
 * without a build.
 */
object ExportUrl {

    /**
     * `structureDelimiter` is sent **empty on purpose**. It is what keeps the
     * exported JSON flat. With a delimiter Tolgee nests the result on every `.`
     * in a key, and a consumer that expects flat keys — ngx-translate, a JSON
     * backed `MessageSource` — silently finds nothing: the build stays green and
     * only the user interface is empty.
     */
    fun build(baseUrl: String, projectId: Int, language: String, tag: String?): String {
        val base = baseUrl.trim().trimEnd('/')
        val url = StringBuilder(base)
            .append("/v2/projects/").append(projectId).append("/export")
            .append("?languages=").append(encode(language))
            .append("&format=JSON")
            .append("&zip=false")
            .append("&structureDelimiter=")
        if (!tag.isNullOrBlank()) {
            url.append("&filterTag=").append(encode(tag.trim()))
        }
        return url.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
