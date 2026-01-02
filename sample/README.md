# Sample Project

This sample project demonstrates how to build and consume an `UmbrellaAAR` from a multi-module project. Follow the steps below to use the latest changes of the plugin and test the `UmbrellaAAR`.

## Using the Latest Changes of the Plugin

1. **Publish the Plugin to Maven Local**:  
   Before using the latest changes of the plugin, you need to publish it to your local Maven repository. Run the following command in the root directory of the project:

   ```bash
   ./gradlew :plugin:publishToMavenLocal
   ```

2. **Update the Version in `libs.versions.toml`**:  
   After publishing the plugin, update the version in the `libs.versions.toml` file to match the version you just published. For example:

   ```toml
   [plugins]
   umbrella-aar = { id = "io.github.tiper.umbrellaaar", version = "x.x.x-SNAPSHOT" }
   ```

3. **Sync the Project**:  
   Once the version is updated, sync your Gradle project to apply the changes.

## Testing the `UmbrellaAAR`

1. **Publish the `UmbrellaAAR` to Maven Local**:  
   To test the `UmbrellaAAR`, you need to publish it to your local Maven repository. Run the following command:

   ```bash
   ./gradlew :export:publishToMavenLocal
   ```

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

- Always verify that the versions in your `libs.versions.toml` file match the versions of the artifacts published to Maven Local.

- This project includes examples of using AIDL and native code. Refer to the `MainActivity` for details on how these are integrated.
