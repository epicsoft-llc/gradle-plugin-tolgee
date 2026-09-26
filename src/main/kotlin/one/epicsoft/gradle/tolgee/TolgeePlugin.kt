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

        val pull = project.tasks.register(PULL_TASK, PullTranslationsTask::class.java) { task ->
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
            task.description = "Checks the translations against the reference language and/or the fallback language."
            task.languages.set(extension.languages)
            task.outputDir.set(extension.outputDir)
            task.referenceLanguage.set(extension.referenceLanguage)
            task.fallbackLanguage.set(extension.fallbackLanguage)
            // Verification reads the directory the download writes. Ordering only,
            // never dependsOn: verifying must stay offline, and forcing a download
            // on every check is exactly what this plugin avoids elsewhere. Without
            // this, asking for both tasks in one invocation fails validation with
            // "uses this output of task ':pullTranslations' without declaring a
            // dependency" — the order would otherwise be arbitrary.
            task.mustRunAfter(pull)
        }
    }

    companion object {
        const val PULL_TASK = "pullTranslations"
        const val VERIFY_TASK = "verifyTranslations"
        private const val I18N_GROUP = "i18n"
        private const val VERIFICATION_GROUP = "verification"
    }
}
