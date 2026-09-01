# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
the versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
