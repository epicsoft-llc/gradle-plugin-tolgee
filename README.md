# Tolgee Gradle Plugin

Exports the translations of a [Tolgee](https://tolgee.io) project into flat JSON files and checks that
nothing is missing in the language your users fall back to.

The plugin does one thing: it brings translations *into* your repository. It never writes to Tolgee, it
ships no runtime library, and it never hooks itself into your build lifecycle uninvited.

- Plugin id: `one.epicsoft.tolgee`
- License: MIT

---

## Usage

### 1. Declare the plugin repository

The plugin is published to a GitLab package registry that is readable without credentials:

```groovy
// settings.gradle
pluginManagement {
  repositories {
    gradlePluginPortal()
    maven { url = "https://gitlab.com/api/v4/projects/85970987/packages/maven" }
  }
}
```

### 2. Apply and configure

```groovy
// build.gradle
plugins {
  id "one.epicsoft.tolgee" version "1.0.0"
}

tolgee {
  url = "https://tolgee.example.com"
  projectId = 16
  tag = "core"
  languages = ["de", "en"]
  outputDir = layout.projectDirectory.dir("src/main/resources/messages")
  fallbackLanguage = "en"
}
```

### 3. Run

```bash
./gradlew pullTranslations     # download <language>.json into outputDir
./gradlew verifyTranslations   # check the fallback language, no network
```

---

## Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `url` | `String` | — | **Required.** Base URL of the Tolgee instance. |
| `projectId` | `Int` | — | **Required.** Numeric project id. |
| `languages` | `List<String>` | — | **Required.** Language codes to export. |
| `outputDir` | `Directory` | — | **Required.** Where `<language>.json` is written. |
| `tag` | `String` | — | Export only keys carrying this tag. Without it the whole project is exported. |
| `apiKeyEnv` | `String` | `TOLGEE_API_KEY` | Name of the environment variable holding the API token. |
| `fallbackLanguage` | `String` | — | Language `verifyTranslations` requires a value in. Unset means the task does nothing. |
| `failOnMissingToken` | `Boolean` | `false` | `true` makes a missing token fail the build instead of skipping the download. |

**The token is never part of the configuration** — only the *name* of the environment variable it comes
from. A field that accepts a token eventually contains one, and then it lives in a build file inside a
repository. For the same reason the token is not declared as a task input: that would write it into the
configuration cache and the build cache key.

---

## Tasks

### `pullTranslations`

Downloads one file per language, in parallel, and writes them as flat JSON sorted by key.

- **Without a token** it logs one line and leaves the committed files untouched, so everyone can build
  the project. Set `failOnMissingToken = true` in the job that is supposed to refresh them.
- **All or nothing:** every language is fetched before the first file is written. A half updated set of
  files is worse than a set that is one day old.
- **Never an empty file:** an empty, nested or unparsable response fails the task and leaves what is on
  disk alone. An empty translation file does not look broken — it looks like a product without labels.
- Files are replaced atomically, so an interrupted run cannot truncate one.
- The task always runs when invoked; the remote content changes without any local input changing, so an
  "up-to-date" verdict would be a lie.

### `verifyTranslations`

Reads the committed files and fails when a key has no value in `fallbackLanguage`. The key universe is
the union over all configured languages, so a key that is missing from the fallback file entirely is
caught as well as one that is present but empty. No network access.

---

## Notes

**Sorted keys.** The export order is the server's business and can change between calls. Sorting turns a
single changed string into a single line diff instead of a rewritten file.

**Flat JSON.** The export request sends an empty `structureDelimiter` parameter. Without it Tolgee nests
the result on every `.` in a key, and a consumer that expects flat keys — ngx-translate, a JSON backed
Spring `MessageSource` — finds nothing: the build stays green and only the user interface is empty. The
plugin rejects a nested response rather than writing it.

**HTML characters** such as `<`, `>` and `&` are written as they are. Escaped translation files are
unreadable in review and needlessly different from what Tolgee returned.

**Lifecycle.** Nothing is wired into `assemble` or `build`. If you want that, say so yourself:

```groovy
assemble.dependsOn pullTranslations
```

---

## Development

```bash
./gradlew build    # compile, validate the plugin, run the tests
./gradlew test     # tests only
```

Requires a Gradle daemon on Java 25. Consuming projects may target any Java version.

Built with Gradle 9.7.1 and the Kotlin JVM plugin 2.3.20. The two need not be identical — Gradle 9.7.1
embeds Kotlin 2.4.0 — because this project compiles plain Kotlin sources rather than precompiled script
plugins, where the embedded version does have to match. Keep an eye on it when raising the wrapper: a
genuine incompatibility shows up as `incompatible version of Kotlin` at compile time.

### Layout

```
src/main/kotlin/one/epicsoft/gradle/tolgee/
├── TolgeePlugin.kt          # entry point — registers extension and tasks
├── TolgeeExtension.kt       # the tolgee { } block
├── api/
│   ├── ExportUrl.kt         # builds the export URL
│   └── TranslationJson.kt   # parsing and writing the flat JSON
└── task/
    ├── PullTranslationsTask.kt
    └── VerifyTranslationsTask.kt
```

### Releasing

1. Raise `version` in `gradle.properties`
2. Update the version in this README (plugin block above)
3. Add a `CHANGELOG.md` entry
4. Push a tag `X.Y.Z` — no leading `v`; the CI publishes that version

### Optional package mirror

The build resolves from the Gradle Plugin Portal and Maven Central. To route it through a mirror, set
`GRADLE_PLUGIN_MIRROR_URL` and/or `MAVEN_MIRROR_URL` in the environment, optionally with
`MAVEN_MIRROR_USERNAME` and `MAVEN_MIRROR_PASSWORD`. Nothing about a mirror is stored in this
repository.
