package one.epicsoft.gradle.tolgee

import one.epicsoft.gradle.tolgee.task.PullTranslationsTask
import one.epicsoft.gradle.tolgee.task.VerifyTranslationsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Registers the `tolgee { }` extension and the two tasks.
 *
 * Nothing is hooked into `assemble` or `build` here. A task that reaches out to
 * the network on every build makes offline builds impossible and CI builds slow;
 * the consuming project decides for itself with one line:
 *
 * ```groovy
 * assemble.dependsOn pullTranslations
 * ```
 */
class TolgeePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(TolgeeExtension.NAME, TolgeeExtension::class.java)

        // Resolved through providers so the configuration cache stays valid: the
        // variable is read when the task runs, not while the build is configured.
        val apiKey: Provider<String> = extension.apiKeyEnv.flatMap { project.providers.environmentVariable(it) }

        project.tasks.register(PULL_TASK, PullTranslationsTask::class.java) { task ->
            task.group = I18N_GROUP
            task.description = "Downloads the translations of a Tolgee project into flat JSON files."
            task.url.set(extension.url)
            task.projectId.set(extension.projectId)
            task.tag.set(extension.tag)
            task.languages.set(extension.languages)
            task.outputDir.set(extension.outputDir)
            task.apiKey.set(apiKey)
            task.apiKeyEnv.set(extension.apiKeyEnv)
            task.failOnMissingToken.set(extension.failOnMissingToken)
        }

        project.tasks.register(VERIFY_TASK, VerifyTranslationsTask::class.java) { task ->
            task.group = VERIFICATION_GROUP
            task.description = "Checks that every key has a non-empty value in the fallback language."
            task.languages.set(extension.languages)
            task.outputDir.set(extension.outputDir)
            task.fallbackLanguage.set(extension.fallbackLanguage)
        }
    }

    companion object {
        const val PULL_TASK = "pullTranslations"
        const val VERIFY_TASK = "verifyTranslations"
        private const val I18N_GROUP = "i18n"
        private const val VERIFICATION_GROUP = "verification"
    }
}
