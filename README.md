# UmbrellaAAR

<img src="logo.png" alt="UmbrellaAAR Logo" width="200" />

**UmbrellaAAR** is a Gradle plugin for Kotlin Multiplatform that merges **local** Android sub-libraries (modules) targets from the same project into a single AAR. This helps produce a consolidated artifact when you have multiple Android library modules internally but want to distribute them as a single `.aar`. **Note that this version of the plugin does not include external third-party AARs**—only sub-libraries that exist within the same multi-module project can be merged.

Below, you’ll find motivations, usage examples, advantages, disadvantages, and key notes about the risks of using ASM to relocate classes and resources.

---

## Table of Contents

1. [Motivation](#motivation)
2. [Advantages](#advantages)
3. [Disadvantages & Risks](#disadvantages--risks)
4. [Plugin Usage & Setup](#plugin-usage--setup)
   1. [Applying the Plugin](#applying-the-plugin)
   2. [Configuration & Tasks](#configuration--tasks)
5. [Examples](#examples)
6. [Recommendations](#recommendations)
7. [License](#license)

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

4. **Namespace Relocation**  
   Ensures that any `R` class references from sub-libraries are properly mapped to the main library’s namespace, preventing collisions.

5. **Optimized Multiplatform Builds**  
   By supporting multi-module KMP setups, the plugin allows for parallel compilation, significantly speeding up the development process.

6. **Avoiding Package Conflicts**  
   External dependencies remain separate and are resolved by the consumer, minimizing conflicts and ensuring compatibility with other libraries.

---

## Disadvantages & Risks

1. **Increased Complexity**  
   Merging multiple local libraries into one AAR involves tasks like relocating classes, merging manifests, and re-routing resources. If something goes wrong, it can break the final `.aar`.

2. **Potential for Resource Conflicts**  
   If multiple local modules share resource names, they can clash in the merged artifact, causing overrides or errors.

3. **Bytecode Manipulation**  
   Using ASM to relocate `R` classes is delicate. If your modules use reflection, dynamic class loading, or other advanced features, relocation might cause unforeseen issues.

4. **Loss of Granular Dependency Management**  
   If you typically want to update or exclude an internal sub-library, merging them into a single AAR can make it harder to isolate changes.

5. **Obfuscation/ProGuard/R8**  
   Obfuscation might affect relocation logic, and you’ll need careful testing to ensure everything still works.

---

## Plugin Usage & Setup

### Applying the Plugin

Add the repository on your `settings.gradle` (Kotlin DSL example):

GitHub (requires authentication):
> **Note**: GitHub packages still requires authentication althought the pakcages are completely public.
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
   }
}
```

Jitpack (no authentication) :
```kotlin
pluginManagement {
   repositories {
      maven {
         url = uri("https://jitpack.io")
         content { includeGroup("io.github.tiper.umbrellaaar") }
      }
   }
}
```

Add the plugin in your module-level `build.gradle` (Kotlin DSL example):

```kotlin
plugins {
    id("io.github.tiper.umbrellaaar")
}
```

> **Note**: This plugin is designed to merge local modules **only**. It does **not** merge external AARs from third-party dependencies.  
> For Kotlin Multiplatform (KMP), ensure that you’re applying this plugin to your **Android library** module within the KMP structure.

### Configuration & Tasks

1. **Local Dependencies**  
   Add your **local** library modules in the plugin’s dedicated configuration (e.g., `export`) so the plugin knows which modules to merge. For instance:

   ```kotlin
   dependencies {
       export(project(":localModuleOne"))
       export(project(":localModuleTwo"))
   }
   ```

   The plugin will only merge these local modules, ignoring any external libraries like `com.example:some-aar:1.0.0`.

2. **Tasks** (Typical Flow)
   - `ensure<BuildType>DependenciesBuilt`: Ensures that local modules have their outputs ready (AAR/JAR).
   - `extract<BuildType>Classes`: Extracts and merges `.jar` files from local modules.
   - `extract<BuildType>Resources`: Unpacks resources from each local library.
   - `extract<BuildType>MainClasses`: Extracts the classes of your main library module.
   - `merge<BuildType>UmbrellaAARResources`: Merges all resources from local modules + your main library.
   - `merge<BuildType>UmbrellaAARClasses`: Merges class files into a single JAR.
   - `relocate<BuildType>UmbrellaAARRClasses`: Uses ASM to relocate `R` classes to your main library’s namespace.
   - `bundle<BuildType>UmbrellaAAR`: Final step—packages everything as a single `.aar`.
   - `extract<BuildType>Sources`: Extracts Java/Kotlin sources from local modules (if relevant).
   - `merge<BuildType>UmbrellaAARSources`: Merges them with your main library’s source for a single `-sources.jar`.

---

## Examples

**Minimal Multi-Module Example**

```kotlin
// In your library module’s build.gradle.kts:
plugins {
    id("com.android.library")
    id("io.github.tiper.umbrellaaar")
}

android {
    namespace = "com.example.myMainLibrary"
    compileSdk = 33
    dependencies {
        export(project(":localModuleOne"))
        export(project(":localModuleTwo"))
    }
}
```

To build the merged `.aar`:

```bash
./gradlew bundleDebugUmbrellaAAR
```

This outputs the merged `.aar` in:
```
build/outputs/umbrellaaar/myMainLibrary-debug.aar
```
If you also want a merged `-sources.jar`:

```bash
./gradlew androidDebugUmbrellaAARSourcesJar
```

Which outputs in:
```
build/outputs/umbrellaaar/myMainLibrary-debug-sources.jar
```

---

## Recommendations

1. **Keep Local Sub-libraries Minimal**  
   Less complexity means fewer resource or class collisions. If a sub-library is large or complicated, consider distributing it separately.

2. **Test Thoroughly**  
   Use unit tests, instrumentation tests, and check different build types (Debug/Release) to ensure the final artifact behaves identically to separate modules.

3. **Monitor for Resource Collisions**  
   Adhere to naming conventions across modules to reduce accidental conflicts (e.g., prefix resource names with your module name).

4. **Reflection and Obfuscation**  
   If your local modules rely on reflection or advanced ProGuard/R8 settings, double-check that relocating `R` classes does not cause breakage.

5. **Be Cautious with Versioning**  
   Ensure all local modules are on the same version schedule. Mismatched versions can be tricky to handle once merged.

6. **ASM Considerations**  
   Bytecode manipulation is extremely powerful but can be hazardous:
      - If a local module uses reflection or dynamic class loading, relocated classes might not be found under their new names.
      - Resource collisions or unusual build logic can break merging steps.
   
   ***In short***: Thoroughly test your final `.aar` after merging local libraries, especially if your modules have advanced or custom code.

---

**Enjoy your single, merged artifact—just keep in mind that merging local modules introduces complexity that must be managed through careful testing and naming conventions!**

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
