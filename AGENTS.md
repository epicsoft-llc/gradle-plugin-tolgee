# AGENTS.md — Tolgee Gradle Plugin

Guidance for coding agents working in this repository. User documentation lives in [`README.md`](README.md).

Gradle plugin `one.epicsoft.tolgee`: exports the translations of a Tolgee project as flat JSON files and checks that
nothing is missing in the fallback language.

| | |
|---|---|
| Plugin ID | `one.epicsoft.tolgee` |
| Group / artifact | `one.epicsoft` / `epicsoft-gradle-plugin-tolgee` |
| Version | `gradle.properties` → `version` |
| Main class | `one.epicsoft.gradle.tolgee.TolgeePlugin` |
| Tasks | `pullTranslations`, `verifyTranslations` |
| Repository | developed on `gitlab.com/epicsoft-networks/gradle-plugin-tolgee` (branch `main` only), mirrored to `github.com/epicsoft-llc/gradle-plugin-tolgee` |
| Plugin Portal | `plugins.gradle.org/plugin/one.epicsoft.tolgee`, since 1.1.2 |

## Rules

- **Public repository, MIT.** The project language is **English**: code, identifiers, comments, README, CHANGELOG,
  commit messages.
- **No secrets and no internal addresses in the repository** — no token, no password, no registry address, not even
  as an example value. Examples use `tolgee.example.com`. A package mirror can only be switched on through environment
  variables (`GRADLE_PLUGIN_MIRROR_URL`, `MAVEN_MIRROR_URL`, `MAVEN_MIRROR_USERNAME`, `MAVEN_MIRROR_PASSWORD`);
  address and credentials live only locally and in CI variables.
- **Do not name the AI tool** in files, commit messages or trailers, or merge request texts. The only exception is
  `.gitignore`, which keeps tool-specific local files out of the repository.
- **Never commit, push, merge, rebase or tag.** Changes stay in the working tree. Proposed commit messages follow
  **Conventional Commits** (`feat:`, `fix:`, `ci:`, `docs:`, `refactor:`, `revert:`).
- **Only a human suppresses warnings.** No `@Suppress`, no `// noinspection`, no `NOSONAR` — fix the cause or report
  the warning. Test code is the exception.
- **Keep this file current** — update it after every architectural change, new parameter, CI change or new rule.

## Commands

```bash
./gradlew build    # compile, validate the plugin, run the tests
./gradlew test     # tests only
./gradlew publishPlugins --validate-only   # checks the Plugin Portal metadata, uploads nothing
./gradlew checkLicense    # runtime dependencies against allowed-licenses.json
./gradlew --init-script ci/dependency-updates.init.gradle checkDependencyUpdates --no-configuration-cache
```

The tests cover the export URL, the JSON handling and the tasks end to end via `GradleRunner` against a local HTTP
stub — including a run with `--configuration-cache` and `--warning-mode=fail` and one with both tasks in a single
invocation.

**Checking for updates** with the sister project, without adding it here: an init script that applies
`one.epicsoft.gradle.DepsUpdatePlugin` from the built JAR of `gradle-plugin-depcheck` (plus Gson) to the root project,
then `./gradlew --init-script <file> checkDependencyUpdates --no-configuration-cache`.

The Gradle daemon needs Java 25. Consuming projects may target any Java version.

## Layout

```
src/main/kotlin/one/epicsoft/gradle/tolgee/
├── TolgeePlugin.kt          # entry point — registers the extension and both tasks
├── TolgeeExtension.kt       # the tolgee { } block
├── api/
│   ├── ExportUrl.kt         # builds the export URL (pure, therefore testable)
│   └── TranslationJson.kt   # reads and writes the flat JSON
└── task/
    ├── PullTranslationsTask.kt
    └── VerifyTranslationsTask.kt
```

## Decisions that are not obvious

- **`structureDelimiter=` stays empty.** That keeps the export flat. With a delimiter Tolgee nests at every `.` in a
  key, and ngx-translate or a JSON `MessageSource` find nothing any more — the build stays green, only the UI is
  empty. `TranslationJson.parse` therefore rejects a nested response explicitly.
- **The token is `@Internal`, not a task input.** As an input it would end up in the configuration cache and in the
  build cache key. Only the *name* of the environment variable is configurable.
- **`outputs.upToDateWhen { false }` on `pullTranslations`.** The remote content changes without any local input
  changing; "up-to-date" would be a lie.
- **Fetch all languages first, then write.** A half-updated set of files is worse than one a day old. Files are
  written through a temp file with `ATOMIC_MOVE`.
- **An empty or unreadable response is an error**, not an empty file. An empty translation file does not look broken,
  it looks like a product without labels.
- **Keys are written sorted.** The server's order can change; otherwise one changed string produces a diff across the
  whole file.
- **`disableHtmlEscaping()`** — otherwise Gson turns `<`, `>`, `&` into Unicode escapes and the file is unreadable in
  review.
- **Without a token nothing fails** (unless `failOnMissingToken = true`): the project must stay buildable for everyone
  without access to the instance.
- **`verifyTranslations.mustRunAfter(pullTranslations)`**, not `dependsOn`. Both tasks work on the same directory;
  without an ordering rule Gradle rejects an invocation with both tasks ("uses this output … without declaring a
  dependency"). `dependsOn` would be wrong: verifying must stay offline and never trigger a download.
- **Nothing hooks into `assemble`.** A task that goes to the network on every build makes offline builds impossible;
  the consuming project writes that one line itself.
- **Kotlin 2.4.21 on Gradle 9.8.2**, although the wrapper embeds Kotlin 2.4.10 (both directions checked, they build
  without warnings). The strict equality the sister project `gradle-plugin-depcheck` demands applies to precompiled
  script plugins (`kotlin-dsl`) — this project compiles plain Kotlin sources. Still look when raising the wrapper: a
  genuine incompatibility shows up as "incompatible version of Kotlin" at compile time.
- **`referenceLanguage` (since 1.1.0) is the master language**: every other language needs a text for each key that
  has one in the reference. `fallbackLanguage` checks the other direction (one language against the union of all).
  Both may be set together; each is a check of its own.
- **`fallbackLanguage` must be listed in `languages`** (`verifyTranslations` checks this). Otherwise
  `pullTranslations` never downloads the file, and the message would blame a missing file instead of the
  configuration.
- **`http://` warns but does not abort** (since 1.0.3): the token would travel unencrypted; whether a Tolgee without
  TLS in a trusted network is acceptable is the consumer's call. Loopback (`localhost`, `127.x`, `::1`) stays quiet —
  the test stub and tunnels never leave the machine. The functional test uses `tolgee.invalid` (never resolves,
  RFC 2606).
- **CI without cache** (since 1.0.3), like the sister project: the pipeline runs rarely, and every dependency change
  created a new cache archive. The test report is kept even when the build fails.
- **The publishing block exists only when `CI_JOB_TOKEN` is set.** That is why a local build never asks for
  credentials — outside CI the repository simply does not exist.
- **`com.gradle.plugin-publish` 2.2.1** publishes to the Gradle Plugin Portal and adds the Javadoc JAR. Every plugin in
  `gradlePlugin` needs `tags` and a `compatibility(it)` block; the configuration cache is declared because the
  functional test runs with it.
- **`LIC:plugin`, `SEC:plugin` and `DEP:plugin` run on `main` only** — on pushes and in the pipeline schedule `weekly`
  (Sundays); a tag publishes without them. They check the runtime dependencies, the JARs the plugin brings into a
  consumer's build, which `BLD:plugin` exports with `runtimeDependencies`:
  - `LIC:plugin`: `checkLicense` (`com.github.jk1.dependency-license-report` 3.1.4) against `allowed-licenses.json`,
    permissive licenses only. The task is not configuration-cache compatible; Gradle discards the entry.
  - `SEC:plugin`: OWASP dependency-check, fails from CVSS 5. The NVD data come from a central database through the
    protected `ESN_DEPCHECK_*` variables, only the runners tagged `epicsoft` reach it; without the variables the job
    does not run.
  - `DEP:plugin`: `ci/dependency-updates.init.gradle` applies the newest depcheck plugin from the Gradle Plugin Portal
    without making it part of the build; `failOnUpdates`, `allow_failure`.

## Releasing

**Flow: work on `main`, tag on `main`.** There is no other long-lived branch.

1. Raise `version` in `gradle.properties`
2. Update the version in the README (plugin block)
3. `CHANGELOG.md`: the entry goes under `## [X.Y.Z] - <date>` of the coming release — never `[Unreleased]`, never a
   bare date
4. Tag `X.Y.Z` on `main` and push it — **no leading `v`**

The tag *is* the version; the `PUB:plugin` job publishes it to the GitLab package registry, authenticated with
`CI_JOB_TOKEN`, and `PUB:portal` to the Gradle Plugin Portal with the CI variables `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET` (masked, protected), which plugin-publish reads itself. A new plugin id waits for a manual
review on the Portal; a version there cannot be replaced. The job rules only match `X.Y.Z` or `X.Y.Z.W`; any other tag
publishes nothing.

**GitHub:** the repository reaches GitHub through a GitLab push mirror. After publishing, `REL:github` (image
`epicsoft/ci:latest`) creates the release: it waits for the tag on GitHub, takes the text from the `CHANGELOG.md`
section `## [X.Y.Z]` and attaches the JARs. It needs the CI variable `GITHUB_TOKEN` (masked, protected) — the tags are
protected (`*.*.*`), otherwise the job would not see the token. Without a changelog entry for the tag the job fails.

Registry project id: **85970987**, the repository is public. Since 1.1.2 consumers need no repository (Gradle Plugin
Portal); releases up to 1.1.1 are only in the registry, via `https://gitlab.com/api/v4/projects/85970987/packages/maven`
in their `settings.gradle`.
