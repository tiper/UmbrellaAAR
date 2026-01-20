package io.github.tiper.umbrellaaar.extensions.mocks

import groovy.lang.Closure
import java.io.File
import java.net.URI
import java.util.concurrent.Callable
import org.gradle.api.Action
import org.gradle.api.AntBuilder
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.PathValidation
import org.gradle.api.Project
import org.gradle.api.ProjectState
import org.gradle.api.Task
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.ArtifactHandler
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.DependencyLockingHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.component.SoftwareComponentContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DeleteSpec
import org.gradle.api.file.FileTree
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.SyncSpec
import org.gradle.api.initialization.dsl.ScriptHandler
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logger
import org.gradle.api.logging.LoggingManager
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.Convention
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.plugins.ObjectConfigurationAction
import org.gradle.api.plugins.PluginContainer
import org.gradle.api.plugins.PluginManager
import org.gradle.api.project.IsolatedProject
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.resources.ResourceHandler
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.WorkResult
import org.gradle.normalization.InputNormalizationHandler
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec

internal class MockProject(
    private val group: Any,
    private val name: String,
) : Project {
    override fun getGroup(): Any = group
    override fun setGroup(group: Any) = throw UnsupportedOperationException("Not implemented")
    override fun getVersion(): Any = throw UnsupportedOperationException("Not implemented")
    override fun setVersion(version: Any) = throw UnsupportedOperationException("Not implemented")
    override fun getStatus(): Any = throw UnsupportedOperationException("Not implemented")
    override fun setStatus(status: Any) = throw UnsupportedOperationException("Not implemented")
    override fun getChildProjects(): Map<String?, Project?> = throw UnsupportedOperationException("Not implemented")
    override fun setProperty(name: String, value: Any?) = throw UnsupportedOperationException("Not implemented")
    override fun getProject(): Project = throw UnsupportedOperationException("Not implemented")
    override fun getIsolated(): IsolatedProject = throw UnsupportedOperationException("Not implemented")
    override fun getAllprojects(): Set<Project?> = throw UnsupportedOperationException("Not implemented")
    override fun getSubprojects(): Set<Project?> = throw UnsupportedOperationException("Not implemented")
    override fun task(name: String): Task = throw UnsupportedOperationException("Not implemented")
    override fun task(args: Map<String?, *>, name: String): Task = throw UnsupportedOperationException("Not implemented")
    override fun task(args: Map<String?, *>, name: String, configureClosure: Closure<*>): Task = throw UnsupportedOperationException("Not implemented")
    override fun task(name: String, configureClosure: Closure<*>): Task = throw UnsupportedOperationException("Not implemented")
    override fun task(name: String, configureAction: Action<in Task>): Task = throw UnsupportedOperationException("Not implemented")
    override fun getPath(): String = throw UnsupportedOperationException("Not implemented")
    override fun getBuildTreePath(): String = throw UnsupportedOperationException("Not implemented")
    override fun getDefaultTasks(): List<String?> = throw UnsupportedOperationException("Not implemented")
    override fun setDefaultTasks(defaultTasks: List<String?>) = throw UnsupportedOperationException("Not implemented")
    override fun defaultTasks(vararg defaultTasks: String?) = throw UnsupportedOperationException("Not implemented")
    override fun evaluationDependsOn(path: String): Project = throw UnsupportedOperationException("Not implemented")
    override fun evaluationDependsOnChildren() = throw UnsupportedOperationException("Not implemented")
    override fun findProject(path: String): Project? = throw UnsupportedOperationException("Not implemented")
    override fun project(path: String): Project = throw UnsupportedOperationException("Not implemented")
    override fun project(path: String, configureClosure: Closure<*>): Project = throw UnsupportedOperationException("Not implemented")
    override fun project(path: String, configureAction: Action<in Project>): Project = throw UnsupportedOperationException("Not implemented")
    override fun getAllTasks(recursive: Boolean): Map<Project?, Set<Task?>?> = throw UnsupportedOperationException("Not implemented")
    override fun getTasksByName(name: String, recursive: Boolean): Set<Task?> = throw UnsupportedOperationException("Not implemented")
    override fun getProjectDir(): File = throw UnsupportedOperationException("Not implemented")
    override fun file(path: Any): File = throw UnsupportedOperationException("Not implemented")
    override fun file(path: Any, validation: PathValidation): File = throw UnsupportedOperationException("Not implemented")
    override fun uri(path: Any): URI = throw UnsupportedOperationException("Not implemented")
    override fun relativePath(path: Any): String = throw UnsupportedOperationException("Not implemented")
    override fun files(vararg paths: Any?): ConfigurableFileCollection = throw UnsupportedOperationException("Not implemented")
    override fun files(paths: Any, configureClosure: Closure<*>): ConfigurableFileCollection = throw UnsupportedOperationException("Not implemented")
    override fun files(paths: Any, configureAction: Action<in ConfigurableFileCollection>): ConfigurableFileCollection = throw UnsupportedOperationException("Not implemented")
    override fun fileTree(baseDir: Any): ConfigurableFileTree = throw UnsupportedOperationException("Not implemented")
    override fun fileTree(baseDir: Any, configureClosure: Closure<*>): ConfigurableFileTree = throw UnsupportedOperationException("Not implemented")
    override fun fileTree(baseDir: Any, configureAction: Action<in ConfigurableFileTree>): ConfigurableFileTree = throw UnsupportedOperationException("Not implemented")
    override fun fileTree(args: Map<String?, *>): ConfigurableFileTree = throw UnsupportedOperationException("Not implemented")
    override fun zipTree(zipPath: Any): FileTree = throw UnsupportedOperationException("Not implemented")
    override fun tarTree(tarPath: Any): FileTree = throw UnsupportedOperationException("Not implemented")
    override fun <T : Any?> provider(value: Callable<out T?>): Provider<T?> = throw UnsupportedOperationException("Not implemented")
    override fun getProviders(): ProviderFactory = throw UnsupportedOperationException("Not implemented")
    override fun getObjects(): ObjectFactory = throw UnsupportedOperationException("Not implemented")
    override fun getLayout(): ProjectLayout = throw UnsupportedOperationException("Not implemented")
    override fun mkdir(path: Any): File = throw UnsupportedOperationException("Not implemented")
    override fun delete(vararg paths: Any?): Boolean = throw UnsupportedOperationException("Not implemented")
    override fun delete(action: Action<in DeleteSpec>): WorkResult = throw UnsupportedOperationException("Not implemented")
    override fun javaexec(closure: Closure<*>): ExecResult = throw UnsupportedOperationException("Not implemented")
    override fun javaexec(action: Action<in JavaExecSpec>): ExecResult = throw UnsupportedOperationException("Not implemented")
    override fun exec(closure: Closure<*>): ExecResult = throw UnsupportedOperationException("Not implemented")
    override fun exec(action: Action<in ExecSpec>): ExecResult = throw UnsupportedOperationException("Not implemented")
    override fun absoluteProjectPath(path: String): String = throw UnsupportedOperationException("Not implemented")
    override fun relativeProjectPath(path: String): String = throw UnsupportedOperationException("Not implemented")
    override fun getAnt(): AntBuilder = throw UnsupportedOperationException("Not implemented")
    override fun createAntBuilder(): AntBuilder = throw UnsupportedOperationException("Not implemented")
    override fun ant(configureClosure: Closure<*>): AntBuilder = throw UnsupportedOperationException("Not implemented")
    override fun ant(configureAction: Action<in AntBuilder>): AntBuilder = throw UnsupportedOperationException("Not implemented")
    override fun getConfigurations(): ConfigurationContainer = throw UnsupportedOperationException("Not implemented")
    override fun configurations(configureClosure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun getArtifacts(): ArtifactHandler = throw UnsupportedOperationException("Not implemented")
    override fun artifacts(configureClosure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun artifacts(configureAction: Action<in ArtifactHandler>) = throw UnsupportedOperationException("Not implemented")
    override fun getConvention(): Convention = throw UnsupportedOperationException("Not implemented")
    override fun depthCompare(otherProject: Project): Int = throw UnsupportedOperationException("Not implemented")
    override fun getDepth(): Int = throw UnsupportedOperationException("Not implemented")
    override fun getTasks(): TaskContainer = throw UnsupportedOperationException("Not implemented")
    override fun subprojects(action: Action<in Project>) = throw UnsupportedOperationException("Not implemented")
    override fun subprojects(configureClosure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun allprojects(action: Action<in Project>) = throw UnsupportedOperationException("Not implemented")
    override fun allprojects(configureClosure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun beforeEvaluate(action: Action<in Project>) = throw UnsupportedOperationException("Not implemented")
    override fun afterEvaluate(action: Action<in Project>) = throw UnsupportedOperationException("Not implemented")
    override fun beforeEvaluate(closure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun afterEvaluate(closure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun hasProperty(propertyName: String): Boolean = throw UnsupportedOperationException("Not implemented")
    override fun getProperties(): Map<String?, *> = throw UnsupportedOperationException("Not implemented")
    override fun property(propertyName: String): Any? = throw UnsupportedOperationException("Not implemented")
    override fun findProperty(propertyName: String): Any? = throw UnsupportedOperationException("Not implemented")
    override fun getLogger(): Logger = throw UnsupportedOperationException("Not implemented")
    override fun getGradle(): Gradle = throw UnsupportedOperationException("Not implemented")
    override fun getLogging(): LoggingManager = throw UnsupportedOperationException("Not implemented")
    override fun configure(`object`: Any, configureClosure: Closure<*>): Any = throw UnsupportedOperationException("Not implemented")
    override fun configure(objects: Iterable<*>, configureClosure: Closure<*>): Iterable<*> = throw UnsupportedOperationException("Not implemented")
    override fun <T : Any?> configure(objects: Iterable<T?>, configureAction: Action<in T>): Iterable<T?> = throw UnsupportedOperationException("Not implemented")
    override fun getRepositories(): RepositoryHandler = throw UnsupportedOperationException("Not implemented")
    override fun repositories(configureClosure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun getDependencies(): DependencyHandler = throw UnsupportedOperationException("Not implemented")
    override fun dependencies(configureClosure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun getDependencyFactory(): DependencyFactory = throw UnsupportedOperationException("Not implemented")
    override fun getBuildscript(): ScriptHandler = throw UnsupportedOperationException("Not implemented")
    override fun buildscript(configureClosure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun copy(closure: Closure<*>): WorkResult = throw UnsupportedOperationException("Not implemented")
    override fun copy(action: Action<in CopySpec>): WorkResult = throw UnsupportedOperationException("Not implemented")
    override fun copySpec(closure: Closure<*>): CopySpec = throw UnsupportedOperationException("Not implemented")
    override fun copySpec(action: Action<in CopySpec>): CopySpec = throw UnsupportedOperationException("Not implemented")
    override fun copySpec(): CopySpec = throw UnsupportedOperationException("Not implemented")
    override fun sync(action: Action<in SyncSpec>): WorkResult = throw UnsupportedOperationException("Not implemented")
    override fun getState(): ProjectState = throw UnsupportedOperationException("Not implemented")
    override fun <T : Any?> container(type: Class<T?>): NamedDomainObjectContainer<T?> = throw UnsupportedOperationException("Not implemented")
    override fun <T : Any?> container(type: Class<T?>, factory: NamedDomainObjectFactory<T?>): NamedDomainObjectContainer<T?> = throw UnsupportedOperationException("Not implemented")
    override fun <T : Any?> container(type: Class<T?>, factoryClosure: Closure<*>): NamedDomainObjectContainer<T?> = throw UnsupportedOperationException("Not implemented")
    override fun getExtensions(): ExtensionContainer = throw UnsupportedOperationException("Not implemented")
    override fun getResources(): ResourceHandler = throw UnsupportedOperationException("Not implemented")
    override fun getComponents(): SoftwareComponentContainer = throw UnsupportedOperationException("Not implemented")
    override fun components(configuration: Action<in SoftwareComponentContainer>) = throw UnsupportedOperationException("Not implemented")
    override fun getNormalization(): InputNormalizationHandler = throw UnsupportedOperationException("Not implemented")
    override fun normalization(configuration: Action<in InputNormalizationHandler>) = throw UnsupportedOperationException("Not implemented")
    override fun dependencyLocking(configuration: Action<in DependencyLockingHandler>) = throw UnsupportedOperationException("Not implemented")
    override fun getDependencyLocking(): DependencyLockingHandler = throw UnsupportedOperationException("Not implemented")
    override fun getRootProject(): Project = throw UnsupportedOperationException("Not implemented")
    override fun getRootDir(): File = throw UnsupportedOperationException("Not implemented")
    override fun getBuildDir(): File = throw UnsupportedOperationException("Not implemented")
    override fun setBuildDir(path: File) = throw UnsupportedOperationException("Not implemented")
    override fun setBuildDir(path: Any) = throw UnsupportedOperationException("Not implemented")
    override fun getBuildFile(): File = throw UnsupportedOperationException("Not implemented")
    override fun getParent(): Project? = throw UnsupportedOperationException("Not implemented")
    override fun getName(): String = name
    override fun getDisplayName(): String = throw UnsupportedOperationException("Not implemented")
    override fun getDescription(): String? = throw UnsupportedOperationException("Not implemented")
    override fun setDescription(description: String?) = throw UnsupportedOperationException("Not implemented")
    override fun compareTo(other: Project?): Int = throw UnsupportedOperationException("Not implemented")
    override fun getPlugins(): PluginContainer = throw UnsupportedOperationException("Not implemented")
    override fun apply(closure: Closure<*>) = throw UnsupportedOperationException("Not implemented")
    override fun apply(action: Action<in ObjectConfigurationAction>) = throw UnsupportedOperationException("Not implemented")
    override fun apply(options: Map<String?, *>) = throw UnsupportedOperationException("Not implemented")
    override fun getPluginManager(): PluginManager = throw UnsupportedOperationException("Not implemented")
}
