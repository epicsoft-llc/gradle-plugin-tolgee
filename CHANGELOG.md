# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
the versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] - 2026-09-26

### Fixed
- `verifyTranslations` names a missing `outputDir` together with an example value, as `pullTranslations`
  does since 1.0.2, instead of Gradle's generic "property doesn't have a configured value".
- `verifyTranslations` reports a `fallbackLanguage` that is not among `languages` as a configuration
  error. It used to blame a missing file, although `pullTranslations` never downloads that language.
- `pullTranslations` closes its HTTP client after the export instead of leaving it to the garbage
  collector inside a long-lived Gradle daemon.

### Changed
- Built with Gradle 9.8.0 and Kotlin 2.4.20; tests run on JUnit 6.
- The configuration cache test runs with `--warning-mode=fail`, so a deprecation — such as accessing
  `Task.project` at execution time, an error from Gradle 10 on — fails the build.
- CI no longer caches Gradle downloads between pipelines — the pipeline runs rarely, and every
  dependency change created a new cache archive. The test report is kept when the build fails.

## [1.0.2] - 2026-09-01

### Fixed
- A missing `url`, `projectId`, `languages` or `outputDir` now produces the plugin's own message,
  naming the setting and an example value. Gradle's generic "property doesn't have a configured
  value" fired first and hid it.

## [1.0.1] - 2026-09-01

### Fixed
- `verifyTranslations` and `pullTranslations` can be requested in the same invocation. Verification
  reads the directory the download writes, which Gradle rejected as an undeclared dependency; the
  verification task is now ordered after the download with `mustRunAfter`. Ordering only — verifying
  stays offline and never triggers a download.

## [1.0.0] - 2026-09-01

### Added
- Initial release
- Task `pullTranslations` — exports one flat JSON file per language from a Tolgee project,
  languages fetched in parallel, keys sorted, files replaced atomically
- Task `verifyTranslations` — offline check that every key has a value in the fallback language
- Extension `tolgee { }` with `url`, `projectId`, `tag`, `languages`, `outputDir`, `apiKeyEnv`,
  `fallbackLanguage`, `failOnMissingToken`
- Missing token skips the download instead of failing the build, so the project stays buildable
  without access to the instance
- Publishing to the GitLab package registry via `CI_JOB_TOKEN`
