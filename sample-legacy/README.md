# Sample Project

This sample project demonstrates how to build and consume an `UmbrellaAAR` from a multi-module project.

It is the legacy setup: `org.jetbrains.kotlin.multiplatform` with `com.android.library` and `androidTarget()`. That setup is tested with `android.builtInKotlin=false` and `android.newDsl=false` in `gradle.properties`, as [this sample](gradle.properties) has them. Copy those two lines along with its build files.

> **Note**: The UmbrellaAAR plugin is available on **Maven Central** and **Gradle Plugin Portal**. For production use, simply add `id("io.github.tiper.umbrellaaar") version "$umbrellaVersion"` to your plugins block, replacing `$umbrellaVersion` with the [latest release](https://plugins.gradle.org/plugin/io.github.tiper.umbrellaaar).

## Using the Latest Changes of the Plugin (Development)

The sample always builds against the plugin source in this repository. `sample-legacy/settings.gradle.kts` adds `../plugin` as an included build, so a change under `plugin/` applies the next time you run Gradle. There is nothing to publish first, and `gradle/libs.versions.toml`, which this sample shares with the root project, declares no plugin version.

## Testing the `UmbrellaAAR`

1. **Publish the `UmbrellaAAR` to Maven Local**:
   To test the `UmbrellaAAR`, you need to publish it to your local Maven repository. `sample-legacy/app` depends on the published coordinates because a `project(":export")` dependency would get the umbrella module as an ordinary library, not the merged AAR. Run the following command in the root directory of the project:

   ```bash
   ./gradlew :sample-legacy:export:publishAndroidReleaseUmbrellaAarPublicationToMavenLocal
   ```

   This publishes only the umbrella. Don't use `publishToMavenLocal` here: the module's other publications share the umbrella's coordinates, so that run fails before anything is published, naming the publications. See [Configuration & Tasks](../README.md#configuration--tasks) in the main README.

2. **Sync the Project**:
   After publishing the `UmbrellaAAR`, sync your Gradle project to use the locally published artifact.

3. **Verify Module Dependencies**:
   This sample demonstrates how modules that depend on other modules are included in the `UmbrellaAAR`. Ensure that all required modules are properly configured and exported.

## Notes

- Ensure that your `settings.gradle.kts` file is configured to include the local Maven repository:

   ```kotlin
   pluginManagement {
       repositories {
           mavenLocal()
           gradlePluginPortal()
           google()
           mavenCentral()
       }
   }

   dependencyResolutionManagement {
       repositories {
           mavenLocal()
           google()
           mavenCentral()
       }
   }
   ```

- Always verify that the `sample-export-legacy` version in your `libs.versions.toml` file matches the version of the artifact published to Maven Local.

- This project includes examples of using AIDL and native code. Refer to the `MainActivity` for details on how these are integrated.
