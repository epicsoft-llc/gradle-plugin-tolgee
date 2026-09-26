package one.epicsoft.gradle.tolgee

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration of the `tolgee { }` block.
 *
 * The API token is deliberately absent: only the *name* of the environment
 * variable holding it can be configured. A field that accepts a token will
 * eventually contain one, and then it sits in a build file inside a repository.
 */
abstract class TolgeeExtension {

    /** Base URL of the Tolgee instance, e.g. `https://tolgee.example.com`. Required. */
    abstract val url: Property<String>

    /** Numeric id of the Tolgee project. Required. */
    abstract val projectId: Property<Int>

    /** Optional tag filter. When unset, every key of the project is exported. */
    abstract val tag: Property<String>

    /** Language codes to export, e.g. `["de", "en"]`. Required, at least one. */
    abstract val languages: ListProperty<String>

    /** Directory the `<language>.json` files are written to. Required. */
    abstract val outputDir: DirectoryProperty

    /** Name of the environment variable holding the API token. Defaults to `TOLGEE_API_KEY`. */
    abstract val apiKeyEnv: Property<String>

    /**
     * The master language, usually the one texts are written in. `verifyTranslations`
     * requires every other language in [languages] to have a value for every key the
     * reference has a value for. Optional.
     */
    abstract val referenceLanguage: Property<String>

    /**
     * Language every key must be translated into, checked by `verifyTranslations`.
     * This is the language a reader falls back to when their own is missing, so an
     * empty value here shows up as an empty label in the product. Optional; without
     * it and without [referenceLanguage] the verification task does nothing.
     */
    abstract val fallbackLanguage: Property<String>

    /**
     * `false` (default): without a token `pullTranslations` logs one line and leaves
     * the committed files alone, so the build works for everyone. `true`: the missing
     * token fails the build — the setting for a CI job that is supposed to refresh.
     */
    abstract val failOnMissingToken: Property<Boolean>

    init {
        apiKeyEnv.convention(DEFAULT_API_KEY_ENV)
        failOnMissingToken.convention(false)
    }

    companion object {
        const val NAME = "tolgee"
        const val DEFAULT_API_KEY_ENV = "TOLGEE_API_KEY"
    }
}
