# Changelog

## 3.1.0

A correctness release: what you declared is what ships, or the build fails and says why. The yardstick is stock, the same modules consumed as ordinary libraries. A build that worked on `3.0` can now fail, warn or publish something different, so read this before you upgrade.

### Fails now

Each failure says what is wrong, and most print the way out as well.

- **Two publications at one set of coordinates.** `publish`, `publishToMavenLocal`, `publishAllPublicationsTo<Repository>Repository` and a plugin's `publishToMavenCentral` now fail before any task runs when two publications would reach one repository with one set of coordinates and one is an umbrella's. That is the default: `androidRelease…` and `androidDebug…` share coordinates, and a KMP umbrella shares them with `kotlinMultiplatform`. `3.0` warned and the last task won.
  *Way out:* publish only the umbrella with `publishAndroidReleaseUmbrellaAarPublicationTo<Repository>Repository`, or `publishAndroidReleaseUmbrellaAarPublicationToMavenLocal`, which keeps your coordinates. To keep a plain `publish`, leave every build type but release out with `enabled = false` on its tasks: where that ends the clash the failure prints the block to paste, and `onlyIf` does not count, as it only decides once the run is under way. Or give each publication its own `artifactId`, which the failure prints as well. While release shares its coordinates with another publication, such as `kotlinMultiplatform`, leaving the others out does not end it, and the umbrella's publications all move, so consumers follow the new name. On `com.android.library` you can disable every variant but the one you publish through `androidComponents` instead. Set `umbrellaaar.allowCoordinateClash=true` in the root `gradle.properties`, or with `-P`, if one is meant to replace the other.
- **`export(...)` of anything but a local module or a platform.** A coordinate, `files(...)`, or a project asked for a capability, a configuration or attributes, or with `isTransitive = false`, now fails the build. `3.0` ignored them.
  *Way out:* declare an external library with `api(...)` or `implementation(...)`, and move files into a module of their own. An export of a project takes none of those options: remove them, and use `exclude(...)` to leave a module out.
- **Anything that does not resolve at or below an export.** This now fails before any task runs, naming the module, how it was reached and Gradle's reason. It covers a dependency of the umbrella or of a merged module, and a version conflict only the umbrella's graph has.
  *Way out:* use `exclude(group = ..., module = ...)` on `export` to leave it out on purpose. A flavor or fallback only a merged module selects has to be declared on the umbrella too. For a version conflict, align the versions: exclude would take that dependency out of the POM and the `.module` as well.
- **An export that a substitution rule turned into an external module**, as it would no longer merge. `3.0` ignored the rule and merged the project. A rule pointing at another local module does not fail: that module merges instead.
  *Way out:* drop the rule on the umbrella module, or drop that export and declare the external module with `api(...)`.
- **A module reached through two variants**, as any `testFixtures(project(...))` is, because a module merges as one archive.
  *Way out:* depend on one of them, move what the other holds into a module of its own, or exclude the module.
- **Two local modules with one `group:name`**, or a local module that loses to an external one with its coordinates, as Gradle keeps one of them.
  *Way out:* give one another group or name, or exclude it.
- **An umbrella merging another umbrella** when that would lose what the inner one exports, as an export is no dependency of the module declaring it.
  *Way out:* export those modules from the outer umbrella too, or exclude them.
- **A dependency with no group or no version, with the POM plugin applied.** A file in a `flatDir` has no group, and a dependency can end up with no version anywhere. Both now fail bundling and publishing.
  *Way out:* publish the file to a repository and depend on that. Declare a version, or give the project in the included build that provides it one.
- **A merged module that cannot be configured.** `3.0` warned and went on with part of the graph.
  *Way out:* fix what stops it from configuring. If that is a circular reference, do not resolve the umbrella while projects are still being configured.
- **A Kotlin Gradle plugin this version cannot read.** `3.0` went on, and could publish less than the modules declare.
  *Way out:* use a Kotlin version the [README lists](README.md#compatibility-matrix), or update UmbrellaAAR.
- **A KMP umbrella with no runtime classpath for its Android target**, which would lose its own dependencies from the POM.
  *Way out:* declare the target with `kotlin { android { } }`. If it is there, check the Kotlin version.
- **A merged class reading an `R` field the merged `R.txt` does not have.** The failure names the field, the class and the module. `3.0` shipped a class that throws `NoSuchFieldError`.
  *Way out:* declare the resource in a merged module, or stop reading it.
- **Attr definitions a consumer rejects** with `Duplicate value for resource`, and an `<item type="attr">` that a styleable names. The failure names both modules. A stock consumer of those modules fails as well.
  *Way out:* declare the attr once at the top level of one module and refer to it with `<attr name="..."/>` in each styleable, or rename it.
- **Styleables on one `R` field**, like `Foo` with `bar_baz` and `Foo_bar` with `baz`, or `Foo` with `bar` and a styleable named `Foo_bar`, naming both modules. A stock consumer has an `R` per namespace. `3.0` shipped one `R`, where a module could read another's attr, or a consumer could not compile a read of that field. A module that does this on its own fails too: stock stays green there, with the same broken `R`.
  *Way out:* rename one of the styleables or attrs, or exclude one of the modules from export and publish it on its own.
- **Paths in one archive that differ by case alone**, like `a.class` and `A.class`, on a disk that cannot hold both, where the second replaced the first. A case-sensitive disk keeps both.
  *Way out:* rename one in its module, or build on a case-sensitive disk.
- **Two modules' paths that differ by case alone**, like `assets/Foo/` and `assets/foo/`, on a disk that cannot hold both, even with identical bytes, naming both modules: the merged AAR would ship them at one path, to every consumer.
  *Way out:* rename one, or build on a case-sensitive disk.
- **A file of one module where another has a folder**, like `assets/foo` and `assets/foo/bar.txt`, on any disk. A stock consumer of the two fails as well.
  *Way out:* rename one.
- **A build type of yours that a merged module has no match for, in a plain `publish`.** A `com.android.library` umbrella now publishes every build type, where `3.0` published release and debug.
  *Way out:* give the build type `matchingFallbacks`, add it to that module, or disable it through `androidComponents`.
- **A selected variant with no archive under its name**, rather than ship another archive. A module with an Android plugin is never merged from a `jvmJar` or a `jar`.
  *Way out:* point the dependency at a variant with an archive, or exclude the module.
- **A task of a variant disabled through `androidComponents`, if you name it.** `publish`, `publishToMavenLocal` and `publishAllPublicationsTo<Repository>Repository` leave that variant out. If a merged module disabled a variant the umbrella still needs, the umbrella fails naming the module.
  *Way out:* enable the variant, or leave its task out of the run. For a merged module's variant, enable it there, disable it on the umbrella as well, or give the umbrella's build type `matchingFallbacks`.
- **A KMP umbrella on a merged module left with a build type it did not ask for**, as when that module disabled `release`. `3.0` shipped what Gradle selected, a debug build in a release publication at worst.
  *Way out:* enable the variant there, or add that build type to `selectBuildTypeFrom` in `localDependencySelection`.
- **A Gradle consumer asking for a version a merged module ruled out.** `strictly` and `reject` now reach consumers through the `.module`, so that consumer's build fails, as it does with stock.
  *Way out:* align the versions.

### Warns now

Warnings print on the build that did the work, see [Known limitations](README.md#known-limitations).

- A module with no sources jar is left out of the merged sources jar, with a warning that names what gives it one, and publishing goes on. `3.0` failed at the last step.
- The `compileOnly` warning now also covers `compileOnlyApi`, flavor scopes and the umbrella module's own compile scopes. An `exclude(...)` of such a module ends it.
- A local module left out with `exclude(...)`, and one the umbrella depends on without exporting it, as they are neither merged nor in the POM.
- An exclude that left nothing out. It names the real group of a local module with that name, and says which build types it went by.
- Files a JVM module depends on, as they ship in neither the AAR nor the POM.
- An umbrella that merges nothing. It says how to add a module.
- Either plugin applied where it can do nothing, and the POM plugin without `maven-publish`.
- Publishing an umbrella with no version, naming the module and its publications, on a configuration cache hit as well.
- Attributes on a dependency, as they do not ship: consumers get the variant their own attributes select.
- A framework export with no Android or JVM target, which is left out.
- The plugin applied from an `afterEvaluate`, as `publish` and `publishToMavenLocal` then still ask for a variant disabled through `androidComponents`. `3.0` built, and so does this.

The warning about shared coordinates on every invocation is gone. A run that would publish over another publication fails instead.

### Changes in what ships

**Which modules merge**

- Gradle's own resolution now decides, with the umbrella's Android runtime attributes, where `3.0` walked configurations by name. Flavors, `matchingFallbacks` and substitution rules apply, and a `com.android.library` umbrella ships a KMP module's Android side, where `3.0` could merge its `jvmJar`.
- Each module's AAR and sources jar come from the variant Gradle selected.
- Custom build types of a `com.android.library` umbrella now get tasks and a publication, so a plain `publish` covers them too.
- `umbrellaAar { includeFrameworkExports = true }` is new. It merges what the module's Apple frameworks export.

**The POM and the `.module`**

- Each publication now comes from a software component and publishes a `.module` next to the POM, as an AGP library does, so Gradle consumers select the umbrella's Android variant by its attributes.
- Both now carry what merged modules declare: exclusions, `isTransitive = false`, classifiers and artifact types. Constraints go to `dependencyManagement` in the POM and `dependencyConstraints` in the `.module`. Requested capabilities are in the `.module` alone. `strictly` and `reject` go out as constraints.
- A transitive module that a constraint, platform or resolution rule moved is now published directly, at `compile` scope, so the POM can gain entries.
- A rule on a merged module's configuration that matches a dependency declared on it now takes that dependency out of the POM and the `.module`, as Gradle resolves the module without it. `3.0` listed it, and so does a stock publication.
- A direct dependency is published at the version the whole umbrella resolves to, so a constraint or a resolution rule of the build can change it.
- `kotlin-stdlib`, which KGP adds late and `3.0` did not always see, now comes from Gradle's resolution, so the POM can gain that line.
- A BOM or a `java-platform` project now stays in the resolution, where it gives versions, and no longer goes out as a library. `platform(project(...))` of an Android library merges that library.
- An `androidTarget()` module now publishes exclusions for `kotlin-stdlib-common`, `kotlin-test-common` and `kotlin-test-annotations-common`, which KGP puts on its configurations, as a stock `.module` does. A build script that did not change gets a longer POM.
- A dependency declared as `@aar`, or any other `@ext`, now publishes as not transitive, so consumers no longer get what it depends on from the umbrella. Set `isTransitive = true` on it to keep that.
- A dependency an included build provides is published with the version it was asked for, not the project's own.
- Every external dependency is still `compile` scope in the POM, and the `.module` lists it in both variants.

**The AAR**

- Modules now merge in the order a consumer of them ranks them, where `3.0` went by folder name. So manifest markers such as `tools:replace`, and the order of `proguard.txt` and `R.txt`, can come out differently. Two checkouts give the same bytes.
- A resource declared twice in `res/values*` now ships once, and a warning names the modules. The first stays. `3.0` shipped every declaration.
- Values resources go by their real type, a shared attr ships once, styleables of one name are united, and `R.txt` is merged per symbol.
- File resources and assets of one name no longer fail. The first stays, with a warning, and the ids of the file that goes ship in `res/values/<module>-overridden-ids.xml`.
- Two modules' service files, `annotations.zip` and `baseline-prof.txt` are now combined, and a second `lint.jar` is dropped with a warning naming the modules whose checks do not ship. All of these failed the merge.
- The AAR now carries the strictest `aar-metadata.properties` of all merged modules, where `3.0` kept the umbrella's alone: the highest `minCompileSdk` with that module's minor, the highest minimum AGP version, core library desugaring if any module needs it, and the strictest desugar flavor at the highest version. A consumer can now be held to a requirement `3.0` lost. A key with no rule of its own keeps the first value, with a warning.
- Merge rules now match entries exactly, so `assets/HEADER.txt`, `assets/proguard.txt` and `libs/foo-classes.jar` ship as the files they are.
- Keep rules now follow the umbrella's `R`, in every option and in comments. Rule files in JVM jars under `META-INF/proguard/` join `proguard.txt`. When `classes.jar` holds `META-INF/com.android.tools/` rules, a copy of the merged rules joins them under `META-INF/com.android.tools/r8/`, as a consumer that finds those reads no other rules of the AAR.
- Rule files under `META-INF/com.android.tools/` stay in the folder that says which R8 reads them, and take a module prefix, `<module>-<file>`, so two JVM modules can ship one name.
- Rules are read across lines and comments, as R8 reads them.
- The umbrella module's own classes have their `R` references rewritten as well, and an umbrella of JVM modules alone keeps its package.
- `module-info.class` is left out, at the root and under `META-INF/versions/`.
- `classes.jar` now holds its entries uncompressed, as the AAR deflates it as a whole. The AAR gets smaller, and the jar is larger once unpacked.

**Sources, tasks and reports**

- Two files at one path with different bytes, in the sources of different modules, now both ship, the second under a folder named after its jar. `3.0` kept one, or failed if one was the umbrella module's.
- `android<Variant>UmbrellaAarSourcesJar` is now a lifecycle task with no outputs of its own. A build script that reads its outputs should read those of `merge<Variant>UmbrellaAarSources`, which writes the same file.
- `gradle tasks` now lists only the public tasks, so `collect<Variant>ExternalDependencies` moved out of the `umbrellaaar` group. `--all` shows it, with a description.
- `external-dependencies.txt` keeps a plain dependency's line, and adds `artifact=`, `excludes=` and `capabilities=`, and lines with `constraint` for a scope, with `strictly=`, `prefer=` and `reject=`.
- `merged-modules.txt` now names the variant that reached a module, as in `:feature -> androidRuntimeElements`, so compare its first column with a `3.0` report.
- A bundle names its reports in one line at the end of its log.
- With `maven-publish`, the `dependencies` report walks the umbrella's publications, so it stops on an umbrella that cannot be built, with the message a bundle would stop with. `3.0` marked that node `FAILED` and went on.

**Rules and locks**

- An umbrella now resolves under the name `classpath`, the one AGP lets resolve while the task graph forms, so a rule on every configuration reaches it. A rule that goes by the name `umbrella<BuildType>Classpath`, or by a filter that matches it, does not, except for an `exclude`.
- That name is taken in an umbrella module, so a build script or a plugin creating a configuration called `classpath` after this plugin fails with Gradle's `already exists`.
- An umbrella is now locked like any other configuration. Its lines in `gradle.lockfile` read `classpathCopy`, `classpathCopy2` and so on, one per build type in alphabetical order, so run with `--write-locks` once after upgrading, and again after adding a build type.
- `export` no longer resolves. A build script that resolved it should read `merged-modules.txt`, or resolve a configuration of its own.

**Comparing with `3.0`**

`umbrellaaar.legacyGraph=true` goes back to reading configurations by name, so you can compare `merged-modules.txt` and `external-dependencies.txt` between both graphs on `3.1`, before you rely on the new one. It publishes coordinates alone, and skips what needs the resolved graph: what does not resolve, a module reached through two variants, projects sharing coordinates, and dependencies with no group or version. The refusals of `export(...)`, nested umbrellas and every check of the merge still apply. The [README](README.md#configuration--tasks) says which differences to ignore.

### Fixed

- The plugin no longer brings its own `com.android.tools:common`, which could win over AGP's and end in a `NoSuchMethodError`.
- The plugin moves to ASM `9.11.1`. Where AGP sits on a parent classpath its ASM runs instead, and a class too new for it now fails naming the class, its module and the way out.
- The plugins apply in any order next to Kotlin and Android.
- A build on top of an earlier one now ends up where a clean one would. Modules trading bytes or project paths used to keep what they held.

Tested on AGP `9.0.0` / Kotlin `2.2.10` / Gradle `9.1.0` and on AGP `9.5.0` / Kotlin `2.4.21` / Gradle `9.8.2`. The largest build it was tested on is a generated 203-module one, not a real project of that size.
