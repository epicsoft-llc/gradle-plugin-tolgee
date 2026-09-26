package one.epicsoft.gradle.tolgee

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives the plugin through a real Gradle build against a local stub of the
 * Tolgee export endpoint.
 */
class TolgeePluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private lateinit var server: HttpServer
    private val requestUris = CopyOnWriteArrayList<String>()
    private val requestTokens = CopyOnWriteArrayList<String>()
    private var responseStatus = 200
    private var responseBody: (String) -> String = { language -> """{"a.key":"value-$language"}""" }

    @BeforeEach
    fun startStub() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.start()
    }

    @AfterEach
    fun stopStub() {
        server.stop(0)
    }

    @Test
    fun `writes one flat file per language`() {
        writeBuildFile()

        val result = run("pullTranslations")

        assertEquals(TaskOutcome.SUCCESS, result.task(":pullTranslations")?.outcome)
        assertEquals("""{
  "a.key": "value-de"
}
""", translationFile("de").readText())
        assertEquals(2, requestUris.size)
        assertTrue(requestUris.all { it.contains("structureDelimiter=") }, requestUris.toString())
        assertTrue(requestUris.all { it.contains("filterTag=core") }, requestUris.toString())
        assertTrue(requestTokens.all { it == TOKEN }, requestTokens.toString())
        assertTrue(!result.output.contains("uses plain http"), "loopback must not warn: " + result.output)
    }

    @Test
    fun `warns when the token would travel over plain http`() {
        // .invalid never resolves (RFC 2606): the export fails, the warning must come first.
        writeBuildFile(url = "http://tolgee.invalid")

        val result = runAndFail("pullTranslations")

        assertTrue(result.output.contains("'http://tolgee.invalid' uses plain http"), result.output)
    }

    @Test
    fun `keeps the committed files when no token is set`() {
        writeBuildFile()
        translationFile("de").also { it.parentFile.mkdirs() }.writeText(COMMITTED)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withEnvironment(environmentWithout(TOKEN_ENV))
            .withArguments("pullTranslations", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":pullTranslations")?.outcome)
        assertTrue(result.output.contains("keeping the committed translation files"), result.output)
        assertEquals(COMMITTED, translationFile("de").readText())
        assertTrue(requestUris.isEmpty(), "no request may be sent without a token")
    }

    @Test
    fun `fails and keeps the committed file when the export fails`() {
        responseStatus = 500
        writeBuildFile()
        translationFile("de").also { it.parentFile.mkdirs() }.writeText(COMMITTED)

        val result = runAndFail("pullTranslations")

        assertTrue(result.output.contains("HTTP 500"), result.output)
        assertEquals(COMMITTED, translationFile("de").readText())
    }

    @Test
    fun `fails and keeps the committed file when the export is empty`() {
        responseBody = { "{}" }
        writeBuildFile()
        translationFile("de").also { it.parentFile.mkdirs() }.writeText(COMMITTED)

        val result = runAndFail("pullTranslations")

        assertTrue(result.output.contains("returned no keys"), result.output)
        assertEquals(COMMITTED, translationFile("de").readText())
    }

    @Test
    fun `fails on a missing token when failOnMissingToken is set`() {
        writeBuildFile(failOnMissingToken = true)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withEnvironment(environmentWithout(TOKEN_ENV))
            .withArguments("pullTranslations")
            .buildAndFail()

        assertTrue(result.output.contains("'$TOKEN_ENV' is not set"), result.output)
    }

    @Test
    fun `works with the configuration cache, including a reused entry`() {
        writeBuildFile()

        // --warning-mode=fail: a deprecation, e.g. Task.project at execution time
        // (an error from Gradle 10 on), fails the test instead of scrolling past.
        val first = runWith("pullTranslations", "--configuration-cache", "--warning-mode=fail")
        val second = runWith("pullTranslations", "--configuration-cache", "--warning-mode=fail")

        assertEquals(TaskOutcome.SUCCESS, first.task(":pullTranslations")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, second.task(":pullTranslations")?.outcome)
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        // The task must run again on the reused entry: the remote content changes
        // without any local input changing.
        assertEquals(4, requestUris.size, requestUris.toString())
    }

    @Test
    fun `pull and verify can be requested in one invocation`() {
        // Verification reads what the download writes. Without an ordering rule
        // Gradle refuses the build ("uses this output ... without declaring a
        // dependency") — and the order would be arbitrary anyway.
        writeBuildFile()

        val result = run("pullTranslations", "verifyTranslations")

        assertEquals(TaskOutcome.SUCCESS, result.task(":pullTranslations")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyTranslations")?.outcome)
        assertTrue(
            result.output.indexOf("Tolgee: de") < result.output.indexOf("all translated in"),
            "the download must run before the verification: " + result.output,
        )
    }

    @Test
    fun `names the missing setting and an example when the configuration is incomplete`() {
        File(projectDir, "settings.gradle").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
              id "one.epicsoft.tolgee"
            }

            tolgee {
              languages = ["de"]
              outputDir = layout.projectDirectory.dir("i18n")
            }
            """.trimIndent()
        )

        val result = runAndFail("pullTranslations")

        assertTrue(result.output.contains("tolgee.url is not set"), result.output)
        assertTrue(result.output.contains("url = \"https://tolgee.example.com\""), result.output)
    }

    @Test
    fun `verify names a missing outputDir and an example`() {
        writeVerifyOnlyBuildFile(languages = """["de", "en"]""", outputDir = null)

        val result = runAndFail("verifyTranslations")

        assertTrue(result.output.contains("tolgee.outputDir is not set"), result.output)
        assertTrue(result.output.contains("layout.projectDirectory.dir("), result.output)
    }

    @Test
    fun `verify rejects a fallback language that is not among the languages`() {
        writeVerifyOnlyBuildFile(languages = """["de"]""", outputDir = "i18n")
        translationFile("de").also { it.parentFile.mkdirs() }.writeText("""{"a.key":"Eins"}""")
        translationFile("en").writeText("""{"a.key":"One"}""")

        val result = runAndFail("verifyTranslations")

        assertTrue(result.output.contains("tolgee.fallbackLanguage 'en' is not one of tolgee.languages [de]"), result.output)
    }

    @Test
    fun `verify passes when every key is translated in the fallback language`() {
        writeBuildFile()
        translationFile("de").also { it.parentFile.mkdirs() }.writeText("""{"a.key":"Eins"}""")
        translationFile("en").writeText("""{"a.key":"One"}""")

        val result = run("verifyTranslations")

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyTranslations")?.outcome)
    }

    @Test
    fun `verify reports keys that are empty or absent in the fallback language`() {
        writeBuildFile()
        translationFile("de").also { it.parentFile.mkdirs() }.writeText("""{"a.key":"Eins","b.key":"Zwei"}""")
        translationFile("en").writeText("""{"a.key":""}""")

        val result = runAndFail("verifyTranslations")

        assertTrue(result.output.contains("a.key"), result.output)
        assertTrue(result.output.contains("b.key"), result.output)
    }

    // ------------------------------------------------------------------ helpers

    private fun writeBuildFile(failOnMissingToken: Boolean = false, url: String = "http://127.0.0.1:${server.address.port}") {
        File(projectDir, "settings.gradle").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
              id "one.epicsoft.tolgee"
            }

            tolgee {
              url = "$url"
              projectId = 16
              tag = "core"
              languages = ["de", "en"]
              outputDir = layout.projectDirectory.dir("i18n")
              fallbackLanguage = "en"
              failOnMissingToken = $failOnMissingToken
            }
            """.trimIndent()
        )
    }

    private fun writeVerifyOnlyBuildFile(languages: String, outputDir: String?) {
        File(projectDir, "settings.gradle").writeText("""rootProject.name = "consumer"""")
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
              id "one.epicsoft.tolgee"
            }

            tolgee {
              languages = $languages
              fallbackLanguage = "en"
              ${outputDir?.let { "outputDir = layout.projectDirectory.dir(\"$it\")" } ?: ""}
            }
            """.trimIndent()
        )
    }

    private fun run(vararg tasks: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withEnvironment(environmentWith(TOKEN_ENV, TOKEN))
        .withArguments(tasks.toList() + "--stacktrace")
        .build()

    private fun runWith(task: String, vararg arguments: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withEnvironment(environmentWith(TOKEN_ENV, TOKEN))
        .withArguments(listOf(task) + arguments.toList())
        .build()

    private fun runAndFail(task: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withEnvironment(environmentWith(TOKEN_ENV, TOKEN))
        .withArguments(task)
        .buildAndFail()

    private fun translationFile(language: String) = File(projectDir, "i18n/$language.json")

    private fun environmentWith(name: String, value: String): Map<String, String> =
        HashMap(System.getenv()).apply { put(name, value) }

    private fun environmentWithout(name: String): Map<String, String> =
        HashMap(System.getenv()).apply { remove(name) }

    private fun respond(exchange: HttpExchange) {
        requestUris.add(exchange.requestURI.toString())
        requestTokens.add(exchange.requestHeaders.getFirst("X-API-Key").orEmpty())
        val language = Regex("languages=([^&]+)").find(exchange.requestURI.query.orEmpty())?.groupValues?.get(1) ?: ""
        val payload = responseBody(language).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(responseStatus, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }

    private companion object {
        const val TOKEN_ENV = "TOLGEE_API_KEY"
        const val TOKEN = "stub-token"
        const val COMMITTED = "{\n  \"a.key\": \"committed\"\n}\n"
    }
}
