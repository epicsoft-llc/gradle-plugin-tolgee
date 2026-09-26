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
 * Checks the committed translation files without touching the network.
 *
 * The fallback language is what a reader gets when their own language has no
 * value. A key missing there produces an empty button, and that is only ever
 * noticed by looking at the running product — which is exactly the kind of
 * defect a build is supposed to catch.
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
    abstract val fallbackLanguage: Property<String>

    @TaskAction
    fun verify() {
        val fallback = fallbackLanguage.orNull?.takeIf { it.isNotBlank() }
        if (fallback == null) {
            logger.lifecycle("Tolgee: no fallbackLanguage configured — nothing to verify.")
            return
        }

        val directory = outputDir.orNull?.asFile?.toPath() ?: throw GradleException(
            "tolgee.outputDir is not set. Add it to the tolgee { } block, for example: " +
                "outputDir = layout.projectDirectory.dir(\"src/main/resources/messages\")"
        )
        val targets = languages.getOrElse(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
        // pullTranslations only downloads what is in languages; without this the
        // report below would blame a missing file instead of the configuration.
        if (fallback !in targets) {
            throw GradleException("tolgee.fallbackLanguage '$fallback' is not one of tolgee.languages $targets.")
        }
        val loaded = targets.mapNotNull { language ->
            val file = directory.resolve("$language.json")
            if (Files.exists(file)) language to read(file) else null
        }.toMap()

        val fallbackTranslations = loaded[fallback]
            ?: throw GradleException("Tolgee: '$fallback.json' is missing in $directory, but it is the fallback language.")

        // The key universe is the union over all languages: a key can be absent
        // from the fallback file entirely, which is just as broken as an empty value.
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
