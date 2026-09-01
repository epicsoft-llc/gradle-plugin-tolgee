package one.epicsoft.gradle.tolgee.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExportUrlTest {

    @Test
    fun `builds an export url without a tag`() {
        val url = ExportUrl.build("https://tolgee.example.com", 16, "de", null)

        assertEquals(
            "https://tolgee.example.com/v2/projects/16/export" +
                "?languages=de&format=JSON&zip=false&structureDelimiter=",
            url,
        )
    }

    @Test
    fun `appends the tag filter when a tag is set`() {
        val url = ExportUrl.build("https://tolgee.example.com", 16, "en", "core")

        assertTrue(url.endsWith("&filterTag=core"), url)
    }

    @Test
    fun `ignores a blank tag`() {
        val url = ExportUrl.build("https://tolgee.example.com", 16, "en", "   ")

        assertTrue(!url.contains("filterTag"), url)
    }

    @Test
    fun `does not double the slash of a trailing base url`() {
        val url = ExportUrl.build("https://tolgee.example.com/", 16, "de", null)

        assertTrue(url.startsWith("https://tolgee.example.com/v2/"), url)
    }

    @Test
    fun `keeps structureDelimiter present and empty`() {
        // The empty parameter is what keeps the export flat — a missing or filled
        // one nests the JSON and silently empties the consuming application.
        val url = ExportUrl.build("https://tolgee.example.com", 1, "de", "dashboard")

        assertTrue(url.contains("&structureDelimiter=&"), url)
    }

    @Test
    fun `encodes language and tag`() {
        val url = ExportUrl.build("https://tolgee.example.com", 1, "pt-BR", "web ui")

        assertTrue(url.contains("languages=pt-BR"), url)
        assertTrue(url.contains("filterTag=web+ui"), url)
    }
}
