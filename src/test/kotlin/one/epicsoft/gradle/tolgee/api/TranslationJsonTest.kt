package one.epicsoft.gradle.tolgee.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationJsonTest {

    @Test
    fun `parses a flat object`() {
        val parsed = TranslationJson.parse("""{"b.key":"Zwei","a.key":"Eins"}""")

        assertEquals(2, parsed.size)
        assertEquals("Eins", parsed["a.key"])
    }

    @Test
    fun `sorts keys so a single change produces a single line diff`() {
        val parsed = TranslationJson.parse("""{"z":"1","a":"2","m":"3"}""")

        assertEquals(listOf("a", "m", "z"), parsed.keys.toList())
    }

    @Test
    fun `keeps a null value`() {
        val parsed = TranslationJson.parse("""{"a":null}""")

        assertTrue(parsed.containsKey("a"))
        assertNull(parsed["a"])
    }

    @Test
    fun `rejects a nested object because the export was not flat`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            TranslationJson.parse("""{"nav":{"home":"Start"}}""")
        }

        assertTrue(failure.message!!.contains("structureDelimiter"), failure.message)
    }

    @Test
    fun `rejects a payload that is not an object`() {
        assertThrows(IllegalArgumentException::class.java) { TranslationJson.parse("""["a","b"]""") }
    }

    @Test
    fun `rejects a payload that is not json`() {
        assertThrows(IllegalArgumentException::class.java) { TranslationJson.parse("<html>error</html>") }
    }

    @Test
    fun `writes html characters unescaped`() {
        val written = TranslationJson.write(mapOf("a" to """Bitte <b>speichern</b> & warten"""))

        assertTrue(written.contains("<b>speichern</b> & warten"), written)
    }

    @Test
    fun `ends the file with a newline`() {
        assertTrue(TranslationJson.write(mapOf("a" to "b")).endsWith("\n"))
    }

    @Test
    fun `round trips`() {
        val original = mapOf("a.b" to "Ä \" ö", "c" to "d")

        assertEquals(original, TranslationJson.parse(TranslationJson.write(original)))
    }
}
