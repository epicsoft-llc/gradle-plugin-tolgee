package one.epicsoft.gradle.tolgee.task

import one.epicsoft.gradle.tolgee.api.TranslationJson
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks the committed translation files without touching the network, in up to two ways:
 *
 * - **`referenceLanguage`** — the master language, usually the one texts are written in. Every
 *   other configured language needs a value for every key the reference has a value for.
 * - **`fallbackLanguage`** — what a reader gets when their own language has no value. It needs a
 *   value for every key found in any language.
 *
 * A key missing there produces an empty button, and that is only ever noticed by looking at the
 * running product — which is exactly the kind of defect a build is supposed to catch.
 */
@DisableCachingByDefault(because = "Reads a handful of small files; caching would cost more than the check")
abstract class VerifyTranslationsTask : DefaultTask() {

    @get:Input
    abstract val languages: ListProperty<String>

    // @Optional as in PullTranslationsTask: Gradle's own "doesn't have a configured
    // value" would fire first and hide the message below, which names an example.
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val outputDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val referenceLanguage: Property<String>

    @get:Input
    @get:Optional
    abstract val fallbackLanguage: Property<String>

    @TaskAction
    fun verify() {
        val reference = referenceLanguage.orNull?.takeIf { it.isNotBlank() }
        val fallback = fallbackLanguage.orNull?.takeIf { it.isNotBlank() }
        if (reference == null && fallback == null) {
            logger.lifecycle("Tolgee: neither referenceLanguage nor fallbackLanguage configured — nothing to verify.")
            return
        }

        val directory = outputDir.orNull?.asFile?.toPath() ?: throw GradleException(
            "tolgee.outputDir is not set. Add it to the tolgee { } block, for example: " +
                "outputDir = layout.projectDirectory.dir(\"src/main/resources/messages\")"
        )
        val targets = languages.getOrElse(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        // pullTranslations only downloads what is in languages; without this the
        // report below would blame a missing file instead of the configuration.
        listOfNotNull(reference?.let { "referenceLanguage" to it }, fallback?.let { "fallbackLanguage" to it })
            .forEach { (setting, language) ->
                if (language !in targets) {
                    throw GradleException("tolgee.$setting '$language' is not one of tolgee.languages $targets.")
                }
            }
        val loaded = targets.mapNotNull { language ->
            val file = directory.resolve("$language.json")
            if (Files.exists(file)) language to read(file) else null
        }.toMap()

        reference?.let { verifyAgainstReference(it, targets, loaded, directory) }
        fallback?.let { verifyFallback(it, loaded, directory) }
    }

    /** Only keys with a value in the reference count — a key without a text there is not one of its keys. */
    private fun verifyAgainstReference(reference: String, targets: List<String>, loaded: Map<String, Map<String, String?>>, directory: Path) {
        val referenceKeys = (loaded[reference]
            ?: throw GradleException("Tolgee: '$reference.json' is missing in $directory, but it is the reference language."))
            .filterValues { !it.isNullOrBlank() }.keys
        val others = targets.filter { it != reference }
        val missingFiles = others.filter { it !in loaded }
        if (missingFiles.isNotEmpty()) {
            throw GradleException(
                "Tolgee: ${missingFiles.joinToString { "'$it.json'" }} missing in $directory, " +
                    "but checked against the reference language '$reference'."
            )
        }
        val gaps = others.associateWith { language ->
            referenceKeys.filter { loaded.getValue(language)[it].isNullOrBlank() }.sorted()
        }.filterValues { it.isNotEmpty() }

        if (gaps.isNotEmpty()) {
            throw GradleException(buildString {
                append("Tolgee: keys of the reference language '").append(reference).append("' without a value:")
                gaps.forEach { (language, keys) ->
                    append("\n  ").append(language).append(" (").append(keys.size).append("):")
                    keys.take(REPORTED_KEYS).forEach { append("\n    - ").append(it) }
                    if (keys.size > REPORTED_KEYS) {
                        append("\n    … and ").append(keys.size - REPORTED_KEYS).append(" more")
                    }
                }
            })
        }
        logger.lifecycle(
            "Tolgee: all {} keys of '{}' have a value in {}.",
            referenceKeys.size, reference, others.joinToString(", ").ifEmpty { "no other language" },
        )
    }

    /** The key universe is the union over all languages: a key absent from the fallback file is as broken as an empty one. */
    private fun verifyFallback(fallback: String, loaded: Map<String, Map<String, String?>>, directory: Path) {
        val fallbackTranslations = loaded[fallback]
            ?: throw GradleException("Tolgee: '$fallback.json' is missing in $directory, but it is the fallback language.")
        val allKeys = loaded.values.flatMap { it.keys }.toSortedSet()
        val missing = allKeys.filter { key -> fallbackTranslations[key].isNullOrBlank() }

        if (missing.isNotEmpty()) {
            throw GradleException(buildString {
                append("Tolgee: ").append(missing.size).append(" key(s) have no value in the fallback language '")
                append(fallback).append("':")
                missing.take(REPORTED_KEYS).forEach { append("\n  - ").append(it) }
                if (missing.size > REPORTED_KEYS) {
                    append("\n  … and ").append(missing.size - REPORTED_KEYS).append(" more")
                }
            })
        }
        logger.lifecycle("Tolgee: {} keys, all translated in '{}'.", allKeys.size, fallback)
    }

    private fun read(file: Path): Map<String, String?> =
        runCatching { TranslationJson.parse(Files.readString(file, StandardCharsets.UTF_8)) }
            .getOrElse { throw GradleException("Tolgee: cannot read '$file': ${it.message}") }

    private companion object {
        const val REPORTED_KEYS = 20
    }
}
