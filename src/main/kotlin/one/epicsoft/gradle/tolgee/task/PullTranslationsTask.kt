package one.epicsoft.gradle.tolgee.task

import one.epicsoft.gradle.tolgee.api.ExportUrl
import one.epicsoft.gradle.tolgee.api.TranslationJson
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Downloads one flat JSON file per language.
 *
 * Two properties of the implementation matter more than the download itself:
 *
 * 1. **All or nothing.** Every language is fetched first, files are written only
 *    once all of them succeeded. A half updated set of files is worse than a set
 *    that is one day old.
 * 2. **Never an empty file.** An empty or unparsable response fails the task and
 *    leaves what is on disk untouched. An empty translation file does not look
 *    broken, it looks like a product without labels.
 */
@DisableCachingByDefault(because = "Talks to a remote service; the result cannot be cached meaningfully")
abstract class PullTranslationsTask : DefaultTask() {

    // @Optional on purpose: Gradle's own "property 'url' doesn't have a
    // configured value" would fire first and hide the message below, which names
    // the block and an example value.
    @get:Input
    @get:Optional
    abstract val url: Property<String>

    @get:Input
    @get:Optional
    abstract val projectId: Property<Int>

    @get:Input
    @get:Optional
    abstract val tag: Property<String>

    @get:Input
    @get:Optional
    abstract val languages: ListProperty<String>

    @get:OutputDirectory
    @get:Optional
    abstract val outputDir: DirectoryProperty

    /**
     * Deliberately `@Internal`: a token declared as a task input would be written
     * into the configuration cache and the build cache key.
     */
    @get:Internal
    abstract val apiKey: Property<String>

    @get:Input
    abstract val apiKeyEnv: Property<String>

    @get:Input
    abstract val failOnMissingToken: Property<Boolean>

    init {
        // The remote content changes without any local input changing, so an
        // "up-to-date" verdict would be a lie.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun pull() {
        val baseUrl = required(url.orNull, "url", "\"https://tolgee.example.com\"")
        val project = required(projectId.orNull, "projectId", "16")
        val targets = languages.getOrElse(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        if (targets.isEmpty()) {
            throw missing("languages", "[\"de\", \"en\"]")
        }
        val target = outputDir.orNull?.asFile?.toPath() ?: throw missing(
            "outputDir", "layout.projectDirectory.dir(\"src/main/resources/messages\")"
        )

        val envName = apiKeyEnv.get()
        val token = apiKey.orNull?.trim().orEmpty()
        if (token.isEmpty()) {
            if (failOnMissingToken.get()) {
                throw GradleException("Tolgee: environment variable '$envName' is not set.")
            }
            logger.lifecycle("Tolgee: '{}' is not set — keeping the committed translation files.", envName)
            return
        }

        // A warning, not a failure: an instance without TLS on a trusted network is
        // the consumer's call — but it has to be a conscious one.
        if (ExportUrl.isPlainHttpToRemoteHost(baseUrl)) {
            logger.warn("Tolgee: '{}' uses plain http — the API token travels unencrypted. Use https.", baseUrl.trim().trimEnd('/'))
        }
        val filterTag = tag.orNull?.takeIf { it.isNotBlank() }
        val downloaded = download(baseUrl, project, filterTag, targets, token)

        Files.createDirectories(target)
        downloaded.forEach { (language, translations) ->
            writeAtomically(target.resolve("$language.json"), TranslationJson.write(translations))
            logger.lifecycle("Tolgee: {} ({} keys) -> {}", language, translations.size, target.resolve("$language.json"))
        }
    }

    private fun download(
        baseUrl: String,
        project: Int,
        filterTag: String?,
        languages: List<String>,
        token: String,
    ): Map<String, Map<String, String?>> {
        logger.lifecycle(
            "Tolgee: exporting project {}{} from {}",
            project,
            filterTag?.let { " (tag '$it')" } ?: "",
            baseUrl.trimEnd('/'),
        )
        // Closed right away: the daemon outlives the build, and an unclosed client
        // holds its selector thread until garbage collection.
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build().use { client ->
            // One virtual thread per language: the work is waiting, not computing.
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val jobs = languages.associateWith { language ->
                    executor.submit<Map<String, String?>> {
                        export(client, baseUrl, project, language, filterTag, token)
                    }
                }
                return jobs.mapValues { (language, job) ->
                    runCatching { job.get() }.getOrElse { failure ->
                        throw GradleException(
                            "Tolgee export failed for '$language': ${failure.cause?.message ?: failure.message}",
                            failure.cause ?: failure,
                        )
                    }
                }
            }
        }
    }

    private fun export(
        client: HttpClient,
        baseUrl: String,
        project: Int,
        language: String,
        filterTag: String?,
        token: String,
    ): Map<String, String?> {
        val request = HttpRequest.newBuilder(URI.create(ExportUrl.build(baseUrl, project, language, filterTag)))
            .header("Accept", "application/json")
            .header("X-API-Key", token)
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() != HTTP_OK) {
            throw GradleException("HTTP ${response.statusCode()} — ${excerpt(response.body())}")
        }
        val translations = runCatching { TranslationJson.parse(response.body()) }
            .getOrElse { throw GradleException(it.message ?: "unreadable response") }
        if (translations.isEmpty()) {
            throw GradleException(
                "the export returned no keys. Check project id, tag and language; " +
                    "writing an empty file would empty the user interface."
            )
        }
        return translations
    }

    /** Write to a sibling file and move it into place, so an interrupted run cannot truncate the target. */
    private fun writeAtomically(target: Path, content: String) {
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            runCatching {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.recoverCatching {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T : Any> required(value: T?, property: String, example: String): T = value ?: throw missing(property, example)

    private fun missing(property: String, example: String) = GradleException(
        "tolgee.$property is not set. Add it to the tolgee { } block, for example: $property = $example"
    )

    private fun excerpt(body: String): String {
        val trimmed = body.trim().replace(Regex("\\s+"), " ")
        return if (trimmed.length <= EXCERPT_LENGTH) trimmed else trimmed.take(EXCERPT_LENGTH) + "…"
    }

    private companion object {
        const val HTTP_OK = 200
        const val EXCERPT_LENGTH = 200
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(20)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
