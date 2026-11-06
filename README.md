# UmbrellaAAR

<img src="logo.png" alt="UmbrellaAAR Logo" width="200" />

[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/io/github/tiper/umbrellaaar/io.github.tiper.umbrellaaar.gradle.plugin/maven-metadata.xml.svg?colorB=007ec6&label=gradlePluginPortal)](https://plugins.gradle.org/plugin/io.github.tiper.umbrellaaar)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.tiper/umbrellaaar)](https://central.sonatype.com/search?q=g%3Aio.github.tiper)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10%2B-blue.svg)](https://kotlinlang.org/)
[![Gradle](https://img.shields.io/badge/Gradle-9.1.0%2B-blue.svg)](https://gradle.org/)
[![AGP](https://img.shields.io/badge/AGP-9.0.0%2B-green.svg)](https://developer.android.com/build/releases/gradle-plugin)

Gradle plugin for merging multiple Android/KMP modules into a single AAR. Useful when you have a multi-module project but want to ship one consolidated library.

Think of it like publishing an XCFramework from KMP, but for Android - you develop with multiple modules for better build performance, then merge them for distribution.

⚠️ **Only merges local modules from your project.** External dependencies (like AndroidX) stay separate.

> ✅ **The current `3.x` line is validated in this repository with AGP `9.0.0` / Gradle `9.1.0`, and
> downstream on a 160-project Kotlin Multiplatform build with AGP `9.3.1` / Gradle `9.5.1` /
> Kotlin `2.4.10`.**
> If you need the AGP `8.13.0+` / Gradle `8.x` toolchain, use the `2.x` release line instead.
>
> **AGP version compatibility history:**
>
> | Plugin version | Supported AGP range | Notes |
> |---|---|---|
> | `< 2.x` | `8.0.0 – 8.11.x` | Uses the internal `mergeManifests` function (broke in AGP 8.12.1) |
> | *(gap)* | `8.12.x – 8.12.x` | ❌ Not supported — AGP removed the internal API; replacement feature not yet available |
> | `2.x` | `8.13.0+` | Uses the public `ManifestMerger2` builder API with `USES_SDK_IN_MANIFEST_LENIENT_HANDLING` |
> | `3.x` (this version) | `9.0.0+` | Validated on `9.0.0` and `9.3.1` |

### Compatibility matrix

| | Minimum | Validated here | Validated downstream | Notes |
|---|---|---|---|---|
| Gradle | `9.1.0` | `9.1.0` | `9.5.1` | AGP `9.0.0` refuses anything lower. |
| AGP | `9.0.0` | `9.0.0` | `9.3.1` | Both `com.android.library` and `com.android.kotlin.multiplatform.library`. |
| Kotlin | `2.2.10` | `2.4.10` | `2.4.10` | AGP `9.0.0` has a runtime dependency on KGP `2.2.10`, so a lower declared version is upgraded to it. Modules are resolved through their Android runtime variant, so no version-specific configuration names are assumed. |
| Java toolchain | `17` | `17` | `21` | |
| Configuration cache | supported | ✅ | ✅ | Store **and** reuse verified, including POM generation. |
| Project isolation | ❌ not supported | | | Merged modules' AARs are read from their own tasks; see *Known limitations*. |

> The downstream column refers to a 160-project build merging 95 modules into one SDK AAR, verified
> to produce an identical published dependency set to the AGP 8 / `2.x` baseline.

**Umbrella module plugin support**

| Applied to | Variants produced | Extract/merge pipeline |
|---|---|---|
| `com.android.library` | one per declared build type (`release`, `debug`, …) | one per build type |
| `com.android.kotlin.multiplatform.library` | `release` + `debug` (aliases — the plugin is single-variant) | **one shared** `androidMain` pipeline |

The two mix in both directions. Either umbrella can merge `com.android.library` and
`com.android.kotlin.multiplatform.library` modules, and a module with an Android target always
contributes its Android code and resources, even when it also has a `jvm()` target.

## Quick Start

> **Version:** Replace `$umbrellaVersion` with the latest release shown in the badge above, or check the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.tiper.umbrellaaar) / [Maven Central](https://central.sonatype.com/search?q=g%3Aio.github.tiper) directly.

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("io.github.tiper.umbrellaaar") version "$umbrellaVersion"
}

kotlin {
    android {
        namespace = "com.example.mylibrary"
        compileSdk = 36
        minSdk = 24
    }
}

dependencies {
    export(project(":module1"))
    export(project(":module2"))
}
```

> The plugin detects `com.android.kotlin.multiplatform.library` (AGP 9) and maps to the single `androidMain` variant, creating both `release` and `debug` task sets for compatibility.
>
> `android` is the current name of this block. `androidLibrary` also works, but is deprecated from AGP `9.1.0`.
>
> For AGP 8, use the `2.x` release line instead.

Build: `./gradlew bundleReleaseUmbrellaAar`

Output: `build/outputs/umbrellaaar/your-library-release.aar`

---

## Table of Contents

1. [Compatibility matrix](#compatibility-matrix)
2. [Motivation](#motivation)
3. [Advantages](#advantages)
4. [Disadvantages & Risks](#disadvantages--risks)
5. [Plugin Usage & Setup](#plugin-usage--setup)
   1. [Applying the Plugin](#applying-the-plugin)
   2. [Configuration & Tasks](#configuration--tasks)
   3. [Known limitations](#known-limitations)
6. [Examples](#examples)
7. [Recommendations](#recommendations)
8. [License](#license)

---

## Motivation

When developing a multi-module Kotlin Multiplatform project with an Android library target, you might have multiple local modules that depend on each other. Typically, you would build each library and distribute them separately. However, there are times you want to distribute just **one** AAR that includes all local sub-libraries:

- **Single Artifact for Internal Distribution**: Generate one `.aar` containing everything, so internal teams don’t have to manage many modules.
- **Unified Release**: If all modules are versioned, tested, and released in lockstep, a single “fat”/"umbrella" artifact may simplify versioning.
- **Simplify for Consumers**: Provide a single library that includes local submodule code, so consumers don’t have to add multiple modules from your project.
- **Optimized Multiplatform Development**: Allows Kotlin Multiplatform projects to be developed as multi-module setups to take advantage of Gradle’s parallel compilation capabilities, reducing build times.
- **XCFramework Inspiration**: Inspired by how Kotlin Multiplatform XCFrameworks are built, but with a twist: only internal modules are merged. External dependencies are left for the consumer to resolve, avoiding conflicts and taking advantage of dependency resolution optimizations.

---

## Advantages

1. **One AAR for All Modules**
   Distribute a single `.aar` containing classes, resources, and assets from your local sub-libraries. This means fewer steps for the final consumers.

2. **Reduced Setup for End Users**
   People depending on your library only need to add a single dependency rather than referencing multiple local modules from your project.

3. **Merged Sources**
   The plugin can also produce a combined sources JAR with all your submodules’ code for improved debugging and code review.

4. **`R` Reference Rewriting**
   Rewrites references to the sub-libraries’ `R` classes so they point at the main library’s `R`. No class is moved or renamed.

5. **Optimized Multiplatform Builds**
   By supporting multi-module KMP setups, the plugin allows for parallel compilation, significantly speeding up the development process.

6. **Avoiding Package Conflicts**
   External dependencies remain separate and are resolved by the consumer, minimizing conflicts and ensuring compatibility with other libraries.

---

## Disadvantages & Risks

1. **Increased Complexity**
   Merging multiple local libraries into one AAR involves tasks like rewriting `R` references, merging manifests, and re-routing resources. If something goes wrong, it can break the final `.aar`.

2. **Potential for Resource Conflicts**
   If multiple local modules share resource names, they can clash in the merged artifact and fail the build, either while merging or in the consumer.

3. **Bytecode Manipulation**
   Using ASM to rewrite references to `R` classes is delicate. If your modules use reflection, dynamic class loading, or other advanced features, the rewrite might cause unforeseen issues.

4. **Loss of Granular Dependency Management**
   If you typically want to update or exclude an internal sub-library, merging them into a single AAR can make it harder to isolate changes.

5. **Obfuscation/ProGuard/R8**
   Obfuscation might affect the `R` reference rewrite, and you’ll need careful testing to ensure everything still works.

---

## Plugin Usage & Setup

### Applying the Plugin

The plugin is available on **Maven Central** and **Gradle Plugin Portal**. No additional repository configuration is needed—simply add it to your module-level `build.gradle.kts`, next to the Kotlin and Android plugins, in any order:

```kotlin
plugins {
    id("io.github.tiper.umbrellaaar") version "$umbrellaVersion"
}
```

#### UmbrellaAarPom Plugin (Optional)

If you want to **publish your UmbrellaAAR to Maven repositories**, also apply the companion **UmbrellaAarPom** plugin. It automatically generates POM files with correct external dependency declarations:

```kotlin
plugins {
    id("io.github.tiper.umbrellaaar") version "$umbrellaVersion"
    id("io.github.tiper.umbrellaaar.pom") version "$umbrellaVersion"
    id("maven-publish")
}
```

**UmbrellaAarPom Plugin:**

Generates Maven POM files when publishing. It:
- Lists external deps (not bundled ones)
- Handles KMP dependency resolution for Android
- Maps Compose Multiplatform deps to AndroidX equivalents
- Prioritizes Android versions over common when both exist
- Supports dependency exclusions

The plugin automatically creates publications named `android{BuildType}UmbrellaAar` (e.g., `androidReleaseUmbrellaAar`) with both the AAR and sources JAR artifacts.

**Alternative: GitHub Packages**

> **Note**: GitHub Packages requires authentication even for public packages. We recommend using Maven Central or Gradle Plugin Portal instead.

If you need to use GitHub Packages, add it to the plugin repositories in your `settings.gradle.kts`. Keep the other repositories, because AGP and the Kotlin plugin are not published there:

```kotlin
pluginManagement {
   repositories {
      maven {
         url = uri("https://maven.pkg.github.com/tiper/UmbrellaAAR")
         credentials {
            username = "<your_github_username>"
            password = "<your_github_token>"
         }
      }
      gradlePluginPortal()
      google()
      mavenCentral()
   }
}
```


> **Note**: This plugin is designed to merge local modules **only**. It does **not** merge external AARs from third-party dependencies.
> For Kotlin Multiplatform (KMP), ensure that you’re applying this plugin to your **Android library** module within the KMP structure.

### Configuration & Tasks

1. **Local Dependencies**
   Add your **local** library modules to the plugin's `export` configuration so the plugin knows which modules to merge:

   ```kotlin
   dependencies {
       export(project(":localModuleOne"))
       export(project(":localModuleTwo"))
   }
   ```

   `export(...)` takes local modules and platforms. An external library like
   `com.example:some-aar:1.0.0` fails the build, because it would not be merged: declare it with
   `api(...)` or `implementation(...)` to publish it. So does `files(...)`, and a project asked for a
   capability, a configuration or attributes, or with `isTransitive = false`, none of which the
   umbrella could ship as written.

   **Which modules are merged?** Gradle's own dependency resolution decides. Starting from the
   declared modules, the plugin resolves them with the umbrella module's Android runtime
   attributes, so every module contributes exactly what it would contribute to an Android app: the
   `api`, `implementation` and `runtimeOnly` dependencies of its Android runtime variant, including
   everything `commonMain` and custom intermediate source sets feed into it. When a module has no
   Android target, its `jvm()` variant is used instead (it is merged through that target's jar). Test scopes,
   `compileOnly`, metadata and internal build-graph configurations never take part, and the umbrella
   module itself can never end up inside its own graph.

   Each module's AAR and sources jar come from the variant Gradle selected, so `matchingFallbacks`
   and flavor selection apply to the umbrella as they do to any other consumer, and classes and
   sources never come from different variants. A variant with no archive under its name fails the
   build rather than shipping another one. So does a module reached through two variants, as any
   `testFixtures(project(...))` is, since an AAR can only hold one.

   **What you export ships, or the build fails.** A module declared with `export(...)` that Gradle
   cannot resolve fails the build before any task runs, naming the module, how it was reached and
   Gradle's reason. So does anything below it that does not resolve, two local modules sharing a
   `group:name`, of which Gradle keeps one, and an umbrella merging another umbrella without what
   that one exports: an export is no dependency of the module declaring it, so the outer umbrella has
   to export those too. Use `exclude(...)` to leave one out on purpose.

   The external dependencies written to the POM come from the same resolution, so their versions
   honour dependency constraints, platforms and resolution rules exactly as when the modules are
   built. `platform(...)` and `enforcedPlatform(...)` dependencies only pin those versions: they are
   neither merged nor written to the POM, local `java-platform` projects included. A transitive
   module they moved is written as a direct dependency, because consumers would otherwise get the
   version the library asked for. Gradle consumers still pick the highest version they see, so a pin
   below that does not carry over. What a platform says about a module the umbrella does not reach
   is lost, so a consumer that gets that module some other way gets the version it asked for. A
   module's own constraint is published, so declare one where that has to carry over.

   What a module declares about an external dependency ships with it, in the POM and the `.module`,
   as a stock publication of that module writes it: `exclude(...)` on the dependency or on a
   configuration behind the variant Gradle selected, `isTransitive = false`, classifiers and artifact
   types, and requested capabilities, which only the `.module` can express. A module's own constraints
   on external modules are published as well, as `dependencyConstraints` in the `.module` and
   `dependencyManagement` in the POM, so they also reach a consumer that gets those modules through
   another library. A dependency an included build provides is published with the version that was
   asked for. One that nothing gives a version fails the build, bundling as well as publishing, and
   so does a file in a `flatDir`, which has no coordinates a consumer could find.

   One published entry speaks for every module declaring that dependency, and Gradle leaves a module
   out only if every path to it does. So an exclusion is published if all declarations carry it, a
   plain declaration next to `isTransitive = false` stays transitive, and each artifact any of them
   asks for gets an entry of its own. An `exclude` on a dependency on a local module carries over to
   the external dependencies below it, the same way. Only an export reaches a local module, so the
   umbrella module's own line on a module it exports binds nothing, and a rule on one of its own
   configurations binds what the umbrella module declares and no more. A Compose dependency keeps its
   exclusions when it becomes its `androidx` twin. One that asks for an artifact or a capability keeps its coordinates
   instead, as that artifact exists under the name it was declared with.

   > Set `umbrellaaar.legacyGraph=true` in the root `gradle.properties` to fall back to the `3.0` graph
   > walk, which reads each module's configurations by name. It is kept so large builds can diff
   > `merged-modules.txt` and `external-dependencies.txt` between both modes before upgrading. In
   > that diff, ignore ` excludes=` and a `kotlin-stdlib` line, which KGP adds too late for this walk
   > to always see. It publishes coordinates alone: none of what a module declares about a
   > dependency, no constraints, and no failure on a dependency without a group or a version.

   **Keeping the AAR and the XCFramework in sync.** If the same module also builds Apple
   frameworks, it can merge every local module those frameworks export, instead of repeating the
   list:

   ```kotlin
   kotlin {
       iosArm64 { binaries.framework { export(projects.feature) } }
   }

   umbrellaAar {
       includeFrameworkExports = true
   }
   ```

   `export(...)` dependencies keep working alongside it. A framework can export a module with no
   Android or JVM target, which is left out of the AAR with a build warning naming it.

2. **Publishing Configuration (UmbrellaAarPom)**

   If you're using the UmbrellaAarPom plugin to publish your library, configure your publishing setup:

   ```kotlin
   plugins {
       id("io.github.tiper.umbrellaaar") version "$umbrellaVersion"
       id("io.github.tiper.umbrellaaar.pom") version "$umbrellaVersion"
       id("maven-publish")
   }

   group = "com.example"
   version = "1.0.0"

   publishing {
       repositories {
           maven {
               url = uri("https://your-repo.com/maven")
               credentials {
                   username = project.findProperty("maven.username") as String?
                   password = project.findProperty("maven.password") as String?
               }
           }
       }
   }
   ```

   Without `group` and `version`, the publication's version is `unspecified`, and publishing it warns.

   The UmbrellaAarPom plugin will automatically:
   - Create a Maven publication for each variant, from a software component
   - Include your UmbrellaAAR and sources JAR as artifacts
   - Publish Gradle Module Metadata (`.module`) alongside the POM, so Gradle consumers select the
     umbrella's Android variant the same way they select any AGP-published library
   - Declare every external dependency of the merged modules, all visible to consumers at compile
     time (`compile` scope in the POM, both variants in the `.module`)

   **Excluding Dependencies**:

   You can exclude specific dependencies using standard Gradle exclusion syntax:

   ```kotlin
   dependencies {
       export(project(":localModule")) {
           exclude(group = "com.example", module = "unwanted-dep")
       }
   }
   ```

   An `exclude` on any `export` line applies to the whole umbrella, not only to that line. It removes
   matching external dependencies from the POM and matching local modules from the merge, whichever
   module pulled them in. Local modules that an excluded module depends on are still merged. A rule
   that leaves nothing out gets a build warning: a local module has to match by its real group, and
   a dependency that only comes in below another one stays, as consumers get it from that one. The
   warning says which build types it went by, as a rule can do its work in one the build did not
   resolve. A rule that reaches every configuration, as `configurations.configureEach` writes one, is
   Gradle's to apply and gets no such warning.

3. **Tasks**

   `<Variant>` is the publication variant (`Release`, `Debug`, …). `<Pipeline>` is the shared
   extract/merge pipeline key: the build type for `com.android.library`, and `AndroidMain` for
   `com.android.kotlin.multiplatform.library`.

   | Task | Kind | Purpose |
   |---|---|---|
   | `extract<Pipeline>Dependencies` | shared | Unpacks exported dependency AARs/JARs (in parallel) and rewrites `R` references. |
   | `extract<Pipeline>MainClasses` | shared | Extracts the classes of your main library module. |
   | `merge<Pipeline>UmbrellaAarDependencies` | shared | Merges manifests, resources and consumer rules into the main unpacked AAR, and writes `classes.jar` straight from the extracted modules. |
   | `bundle<Variant>UmbrellaAar` | per variant | **Public** — packages everything as a single `.aar`. |
   | `merge<Variant>UmbrellaAarSources` | per variant | Streams every module's sources jar into one merged jar. |
   | `android<Variant>UmbrellaAarSourcesJar` | per variant | **Public** — lifecycle task that runs `merge<Variant>UmbrellaAarSources`. It has no output of its own: to wire the jar into another task, take it from that one. |

   **UmbrellaAarPom plugin tasks** (when applied):

   | Task / name | Purpose |
   |---|---|
   | `collect<Variant>ExternalDependencies` | Collects the external dependencies of all merged modules. |
   | `generatePomFileForAndroid<Variant>UmbrellaAarPublication` | Generates the POM. |
   | `publishAndroid<Variant>UmbrellaAarPublicationTo<Repository>Repository` | Publishes AAR + sources + POM. |
   | publication `android<Variant>UmbrellaAar` | The `MavenPublication` the tasks above belong to. |

   > ⚠️ Upgrading from `2.x`: under `com.android.kotlin.multiplatform.library` the *internal* task
   > names changed from `extractReleaseDependencies` / `mergeReleaseUmbrellaAarDependencies` to
   > `extractAndroidMainDependencies` / `mergeAndroidMainUmbrellaAarDependencies`, because the
   > `release` and `debug` publications now share a single pipeline instead of doing the same work
   > twice. All **public** task and publication names are unchanged. `extract<Variant>Sources` was
   > removed — `merge<Variant>UmbrellaAarSources` now streams the jars directly.

   > 🔴 **Publish the umbrella with its own task, or give it its own coordinates.**
   > The umbrella publication defaults to `project.group:project.name:project.version`, which in a KMP
   > project is *already taken* by the `kotlinMultiplatform` publication — and `androidRelease…` and
   > `androidDebug…` share it with each other. Maven publications are addressed by their coordinates,
   > so two of them going to one repository land in the same location and the last one wins: the
   > umbrella `.aar` survives, but another publication's `.pom` and `.module` replace the flattened
   > dependency list this plugin exists to produce, and consumers then fail on internal project
   > coordinates such as `Could not find <RootProject>.<path>:<module>:unspecified`.
   >
   > So a run that would do that fails before anything is published, naming the module, the
   > publications and the repository. `publish`, `publishToMavenLocal` and a plugin's
   > `publishToMavenCentral` all would, by default. Either publish only the umbrella:
   >
   > ```bash
   > ./gradlew publishAndroidReleaseUmbrellaAarPublicationToMavenLocal
   > ```
   >
   > …or give the publications distinct coordinates, which the failure spells out for your module.
   > In a KMP project both have to move: renaming only `androidReleaseUmbrellaAar` leaves
   > `androidDebugUmbrellaAar` colliding with `kotlinMultiplatform`. On `com.android.library`, moving
   > debug is enough, and so is disabling that variant.
   >
   > ```kotlin
   > publishing {
   >     publications.named<MavenPublication>("androidReleaseUmbrellaAar") {
   >         artifactId = "my-library-umbrella"
   >     }
   >     publications.named<MavenPublication>("androidDebugUmbrellaAar") {
   >         artifactId = "my-library-umbrella-debug"
   >     }
   > }
   > ```
   >
   > A debug build has no use on Maven Central. To leave one out of a run that publishes everything,
   > disable its tasks. `onlyIf` does not count here, as it only decides once the run is under way.
   >
   > ```kotlin
   > tasks.withType<AbstractPublishToMaven>().configureEach {
   >     if (publication.name == "androidDebugUmbrellaAar") enabled = false
   > }
   > ```
   >
   > If one publication is meant to replace the other, set `umbrellaaar.allowCoordinateClash=true` in
   > the root `gradle.properties`, or pass it with `-P`. A module's own `gradle.properties` is not read.
   > Upgrading from `3.0`, an umbrella needs one of these for a plain `publish`, which used to leave
   > whichever publication task order ran last.

   A variant disabled through `androidComponents` has no umbrella: `publish`, `publishToMavenLocal`
   and `publishAllPublicationsTo<Repository>Repository` leave it out, and a task of that variant fails
   if you name it. If a merged module disabled a variant the umbrella still needs, that umbrella fails
   naming the module. Enable the variant, disable it on the umbrella as well, or give the umbrella's
   build type `matchingFallbacks`. A `com.android.kotlin.multiplatform.library` umbrella has neither,
   so it also fails on a merged module left with a build type it did not ask for. Enable the variant,
   or add that build type to `selectBuildTypeFrom` in `localDependencySelection`.

4. **Reports**

   ```
   build/reports/umbrellaaar/<pipeline>/merged-modules.txt        # module path + the edge that pulled it in
   build/reports/umbrellaaar/<variant>/external-dependencies.txt  # exactly what lands in the POM
   ```

   `merged-modules.txt` is written by `extract<Pipeline>Dependencies`.
   `external-dependencies.txt` is written by `collect<Variant>ExternalDependencies`, which only
   exists when the UmbrellaAarPom plugin is applied.

   `merged-modules.txt` is the fastest way to check that nothing unexpected (a demo app, a test
   fixture module, a deliberately excluded feature) crept into the published artifact — diff it
   between two builds. The edge names the module that pulled each one in and the variant it was
   reached through, e.g. `:feature -> androidRuntimeElements`.

   `external-dependencies.txt` is sorted, with one line per published entry. A plain dependency is
   `group:name:version:scope`. Whatever else was declared follows that, separated by spaces, and a
   constraint has `constraint` for a scope:

   ```
   com.squareup.okhttp3:okhttp:4.12.0:compile excludes=*:annotations,com.squareup.okio:*
   com.squareup.okio:okio:3.9.1:constraint
   io.netty:netty-transport-native-epoll:4.1.115.Final:compile artifact=netty-transport-native-epoll:jar:jar:linux-x86_64
   org.jetbrains.kotlin:kotlin-stdlib:2.3.0:compile
   org.jetbrains.kotlin:kotlin-test:2.3.0:compile capabilities=org.jetbrains.kotlin:kotlin-test-framework-junit
   ```

   `artifact=` is `name:type:extension:classifier`, always with four fields, and an empty one for
   what the artifact does not set. `excludes=` is `group:module`, with `*` for either. `*:*` is
   `isTransitive = false`. A constraint's version field is empty if it gives none, and `strictly=`,
   `prefer=` and `reject=` follow it for what else it says of the version.

### Known limitations

- **The umbrella publication does not claim unique coordinates.** It defaults to
  `project.group:project.name:project.version`, so it collides with the `kotlinMultiplatform`
  publication in a KMP project, and `androidRelease…`/`androidDebug…` collide with each other. A run
  that would publish two of them to one repository fails before it starts — see the warning above.
  The plugin fails rather than resolving the clash, because it cannot know which publication you
  want at those coordinates.
- **`compileOnly` project dependencies are not merged.** `compileOnly` means "provided by the
  consumer", so those modules are left out of the AAR. Declare them with `api`/`implementation` if
  they must ship inside the AAR. (Modules reachable only through `compileOnly` are also listed in a
  build warning, because `2.x` *did* merge them. An `exclude(...)` of such a module on `export` says
  it stays out on purpose, which ends the warning.)
- **Excluded or un-exported local modules are neither merged nor declared.** A module removed with
  `exclude(...)`, or one the umbrella module depends on without exporting it, is not in the AAR and
  not in its POM. Both cases are reported in a build warning: if the merged code needs them at
  runtime, publish them separately and have consumers depend on them.
- **A platform's own dependencies are not followed.** A `java-platform` with `allowDependencies()`
  can bring libraries along. The umbrella skips platforms, so those are neither merged nor in its
  POM. Declare them directly on a merged module.
- **`compileOnly` dependencies are not written to the POM** either: every coordinate in the
  generated POM carries `<scope>compile</scope>`, so publishing them would hand consumers, at
  runtime, exactly the dependencies you marked as non-transitive.
- **The published exclusions are those of the runtime variant, in both variants and the POM.** Every
  dependency is published to both, so a rule on `implementation` also binds an `api` dependency, as
  in the `.module` of a stock library but not in its POM.
- **An `androidTarget()` module publishes three exclusions nobody wrote.** KGP puts rules for
  `kotlin-stdlib-common`, `kotlin-test-common` and `kotlin-test-annotations-common` on the
  configurations behind the variant Gradle selects, so each dependency of that module carries them,
  as in the `.module` of a stock library. `3.0` published none, so a build script that did not change
  gets a longer POM.
- **An artifact on its own is not transitive.** Gradle takes `@aar`, or any other `@ext`, to mean
  that file and nothing it depends on, so that dependency publishes with `excludes=*:*`, and
  consumers no longer get what it depends on from the umbrella, as `3.0` gave them. Set
  `isTransitive = true` on the dependency to keep that.
- **A rule on a configuration that matches a dependency declared on it removes that dependency.**
  Gradle resolves the module without it, so it is not published. A stock publication lists it.
- **A dependency is published at the version Gradle selected, with no `prefer`, attributes or
  `because`.** `strictly` and `reject` go out as a constraint on its module, so they bind consumers
  as they do with stock. Attributes are named in a build warning, as consumers select the variant
  themselves. Constraints keep all of this.
- **Project isolation is not supported.** Gradle resolves which modules to merge, but each module's
  AAR and sources jar are still taken from its `bundle<Variant>Aar` / sources-jar task, which means
  reading sibling projects' tasks. AGP does not hand the published AAR to other modules through
  dependency resolution — only a lint-specific copy that also carries the module's `R` classes.
- **Product flavors of a merged module have to be selected.** Declare the selection as you would for
  any other consumer: `missingDimensionStrategy` on a `com.android.library` umbrella,
  `localDependencySelection` on a `com.android.kotlin.multiplatform.library` one. Without it the
  build fails naming the module. `umbrellaaar.legacyGraph=true` cannot select a flavor, and product
  flavors on the umbrella module itself are not supported.
- **The generated POM reflects dependencies as declared at execution time.** The Kotlin plugin adds
  some dependencies lazily — `kotlin-stdlib` appears in every KMP project's `commonMainApi` only
  once a JVM-target project has been configured, for example — so a POM generated in a task graph
  that also builds a desktop target can contain one extra coordinate. It always resolves to the same
  version, but if you need byte-reproducible POMs, generate them in a dedicated invocation
  (`./gradlew :sdk:publish…`) rather than as part of a larger build.
- **`R.txt` is merged, not regenerated.** Symbols are de-duplicated by type and name — the same
  resource in two modules carries a different placeholder ID in each, and the first is kept. A
  styleable two modules share lists the children of both, and the id of an `<enum>` that lost its
  attr leaves with it. IDs are not re-numbered: AAPT2 in the consumer assigns the final IDs.
- **What merged classes read from `R` has to be there.** A class that reads a field of a merged
  module's `R` or of the umbrella's, which the merged `R.txt` does not have, fails the merge, naming
  the field, the class and the module. In a consumer that class would throw `NoSuchFieldError`.
- **Duplicate resources and assets** across merged modules keep their first declaration — the
  umbrella module's own, otherwise the first module in merge order — and are reported in a warning.
  Modules merge the way a consumer of them ranks them, dependents before what they depend on, then
  in the order they are reached from `export(...)`. Folder names play no part.
  Every merged module's resources end up in one AAR, where AGP rejects a resource declared twice
  with "Duplicate resources", so the later declarations are left out. A resource goes by its real
  type: `<string-array>`, `<integer-array>` and `<array>` are one `array`, and `<item type="color">`
  is a `<color>`. The same name in different qualifier folders (`values` vs `values-pt`) is not a
  duplicate. Styleables of one name become their union, and a child both define is the one further
  down the merge order, as in a consumer. An `<attr>` is one attr however many styleables define it,
  so definitions a consumer takes for one are left as one, and the others refer to it. Definitions
  it rejects with "Duplicate value for resource" fail the merge instead, naming both modules, and so
  does an `<item type="attr">`, which is one with a top-level `<attr>`, where a styleable names it. Other
  `res/` folders go by folder and name without extension, so `res/drawable/a.xml` and
  `res/drawable/a.png` are one resource, and `assets/**` by path: the first stays, the same way a
  consumer of those modules overrides one with the other, and the ids of the file that goes stay
  too. Identical bytes keep one copy, quietly.
- **Files that cannot be merged fail the build.** These are merged:
  - `R.txt` (by symbol), `public.txt` and `baseline-prof.txt` (by line) are de-duplicated.
  - `proguard.txt` (and `.pro` files at the AAR root) are concatenated, along with the rule files a
    JVM module carries in `META-INF/proguard/`. Those stop working inside `classes.jar`, so they move
    out of it. A JVM module with `META-INF/com.android.tools/` rules loses its legacy ones to them, as
    in any consumer, and those stay in `classes.jar`, where a copy of the merged rules joins them
    because a consumer that finds them reads no other rules of the AAR.
  - `res/values*`, `.kotlin_module` and `META-INF/com.android.tools/` rule files are namespaced per
    module, the last in the folder that says which R8 reads them.
  - `META-INF/services/*` provider files inside `classes.jar` are combined.
  - `module-info.class` is left out, at the root and under `META-INF/versions/`: one jar is no one JVM
    module, and an AAR's classes run from the class path, which reads none.
  - `annotations.zip` (typedef annotations) is combined, including two modules sharing a package.
  - `aar-metadata.properties` keeps the strictest requirement of every module: the highest
    `minCompileSdk` and minimum AGP version, and core library desugaring if any module needs it.
  - `lint.jar`: an AAR carries only one, so the umbrella's own — or else the first module's — is
    kept, and a warning names the modules whose lint checks are not shipped.

  Anything else contributed by two modules at the same path — `libs/*.jar`, `jni/<abi>/*.so`,
  `navigation.json`, `prefab/**` — is an error naming both contributors, unless the bytes are identical, in which
  case one copy is kept. Rename the file in one of the modules, or move it under a
  module-specific sub-folder. AGP generates `navigation.json` and `prefab/**`, so those two cannot be
  renamed, and two modules with deep links or prefab cannot be merged.

  Two modules' paths that differ by case alone, like `assets/Foo/` and `assets/foo/`, are an error
  naming both on a disk that cannot hold both, as most on macOS and Windows cannot: the merged AAR
  would ship them at one path, to every consumer. Rename one, or build on a case-sensitive disk.
  A file of one module where another has a folder, like `assets/foo` and `assets/foo/bar.txt`, is an
  error naming both on any disk, and a stock consumer of the two fails as well. Rename one.
- **`public.txt` is only published when every module declares one.** In an AAR, `public.txt` is the
  whitelist of public resources: if it is present, everything *not* listed becomes private. A module
  that ships no `public.txt` is saying "all my resources are public". So if only some of the merged
  modules use `<public>`, publishing the union of their declarations would silently make every other
  module's resources private — the merged file is dropped instead, with a warning naming both sets
  of modules. Declare `<public>` in every module contributing resources to publish a public surface.
- **`R` reference rewriting only touches merged modules.** Namespaces are read from each dependency
  AAR's `AndroidManifest.xml`; references to them — from the merged modules *and* from the umbrella
  module's own classes — are rewritten to the umbrella's `R`. A third-party AAR bundled inside one of
  your modules keeps its own `R`. Keep rules go the same way: `com.acme.feature.R$string` becomes
  the umbrella's, and a class name with wildcards that reaches a module's `R`, however it is spelled
  (`com.acme.**`, `**.feature.R$*`), gets the umbrella's `R` listed next to it. A rule on every field
  of such a class (`{ *; }`, `{ public *; }`) would then keep what the other modules declare, so what
  it reached, read from that module's `R.txt`, goes in a rule of its own. `-keepclassmembers` fires
  when the class is live, and the shared one nearly always is, so that rule gets an `-if` on the
  classes that read the module's `R`, and goes when nothing does. A module's `R` that is only live
  because another rule keeps it loses its members that way, so give that rule the members as well.
  An `-if` of a module's own only follows an `R` it names. The bytecode pre-scan that decides
  whether ASM runs at all is best-effort (false positives only cost a no-op rewrite).
- **Mixed-plugin umbrellas and the `debug` publication.** `com.android.kotlin.multiplatform.library`
  has no build types, so the umbrella's own AAR is the same `androidMain` artifact for both
  publications. Exported plain `com.android.library` modules are therefore also taken from one AAR
  for both, **release** unless `localDependencySelection` selects another build type. Publish only
  `release` if that distinction matters to you.
- **Merged sources only hold the modules that have a sources jar.** Kotlin Multiplatform modules have
  one by default; `com.android.library` and `java-library` modules do not. A module without one is
  left out of the merged sources jar, its classes still ship and publishing goes on, and a build
  warning names it with the line that adds one — see *Generating Sources JAR* under
  [Examples](#examples).

---

## Examples

**Minimal Multi-Module Example**

```kotlin
// In your library module's build.gradle.kts:
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("io.github.tiper.umbrellaaar") version "$umbrellaVersion"
}

kotlin {
    android {
        namespace = "com.example.mylibrary"
        compileSdk = 36
        minSdk = 24
    }
}

dependencies {
    export(project(":localModuleOne"))
    export(project(":localModuleTwo"))
}
```

To build the merged `.aar`:

```bash
./gradlew bundleDebugUmbrellaAar
```

This outputs the merged `.aar` in:
```
build/outputs/umbrellaaar/myMainLibrary-debug.aar
```

**Publishing Example (with UmbrellaAarPom)**

```kotlin
// In your library module's build.gradle.kts:
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("io.github.tiper.umbrellaaar") version "$umbrellaVersion"
    id("io.github.tiper.umbrellaaar.pom") version "$umbrellaVersion"
    id("maven-publish")
}

group = "com.example"
version = "1.0.0"

kotlin {
    android {
        namespace = "com.example.mylibrary"
        compileSdk = 36
        minSdk = 24
    }
}

dependencies {
    // Local modules to merge
    export(project(":localModuleOne"))
    export(project(":localModuleTwo"))
}

publishing {
    repositories {
        maven {
            name = "MyMavenRepo"
            url = uri("https://my-maven-repo.com/releases")
            credentials {
                username = findProperty("maven.username") as String?
                password = findProperty("maven.password") as String?
            }
        }
    }
}
```

To publish your library:

```bash
./gradlew publishAndroidReleaseUmbrellaAarPublicationToMyMavenRepoRepository
```

The UmbrellaAarPom plugin will:
- Bundle `:localModuleOne` and `:localModuleTwo` into the AAR
- Generate a POM declaring the external dependencies of the merged modules
- Include sources JAR for better IDE support
- Publish everything to your Maven repository

**Generating Sources JAR**

If you also want a merged `-sources.jar`:

```bash
./gradlew androidDebugUmbrellaAarSourcesJar
```

Which outputs in:
```
build/outputs/umbrellaaar/myMainLibrary-debug-sources.jar
```

The umbrella module and each merged module contribute their own sources jar, and a build warning names any that has none. Kotlin Multiplatform modules have one by default. A `com.android.library` module has to enable it for each variant it contributes:

```kotlin
android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
        singleVariant("debug") {
            withSourcesJar()
        }
    }
}
```

A `com.android.kotlin.multiplatform.library` umbrella takes plain `com.android.library` modules from their `release` variant for both publications, unless `localDependencySelection` selects another build type, so those modules only need `release`.

A `java-library` module needs `java { withSourcesJar() }`.

---

## Recommendations

1. **Keep Local Sub-libraries Minimal**
   Less complexity means fewer resource or class collisions. If a sub-library is large or complicated, consider distributing it separately.

2. **Test Thoroughly**
   Use unit tests, instrumentation tests, and check different build types (Debug/Release) to make sure the final artifact behaves like separate modules.

3. **Watch for Resource Collisions**
   Use naming conventions across modules to avoid accidental conflicts (e.g., prefix resource names with your module name).

4. **Reflection and Obfuscation**
   If your modules use reflection or advanced ProGuard/R8 settings, double-check that rewriting `R` references doesn't break things.

5. **Watch Out for Versioning**
   Make sure all local modules are on the same version schedule. Mismatched versions can be tricky to handle once merged.

6. **ASM Considerations**
   Bytecode manipulation is powerful but can be tricky:
      - If a module looks up its `R` class by name through reflection or dynamic class loading, it won't be found. Only bytecode references are rewritten, so the name still points at the module's own `R`, which the consumer no longer generates.
      - Resource collisions or unusual build logic can break merging steps.

   ***In short***: Test your final `.aar` thoroughly after merging, especially if your modules have advanced or custom code.

7. **Supported Source Sets**
   Dependencies are discovered by resolving each module's **Android** runtime variant, which the
   Kotlin Gradle plugin builds from every source set that actually feeds the Android main
   compilation. Custom intermediate source sets and `applyDefaultHierarchyTemplate()` therefore work
   out of the box, and source sets that never reach Android — `iosMain`, `jvmMain`/`desktopMain`, or a
   hand-rolled `nonAndroidMain` — are correctly ignored even when the module also has a `jvm()`
   target. `api`, `implementation` and `runtimeOnly` are collected for the POM.

---

**Enjoy your single, merged artifact! Just remember that merging modules adds complexity - test thoroughly and use good naming conventions.**

```
When the module shines, we shine together
Told you I'll be here forever
Said I'll always be your friend
Took an oath, I'ma stick it out to the end
Now that it's raining more than ever
Know that we'll still have each other
You can stand under my umbrella
You can stand under my umbrella, AAR, AAR, AAR, AAR, AAR
Under my umbrella, ella, ella, AAR, AAR, AAR
Under my umbrella, ella, ella, AAR, AAR, AAR
Under my umbrella, ella, ella, AAR, AAR, AAR, AAR, AAR, AAR
```

## License

```
Copyright 2024 Tiago Pereira

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
