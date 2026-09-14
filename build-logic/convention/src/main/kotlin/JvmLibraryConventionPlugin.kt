import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Pure-JVM modules. Used for :core:model and :core:common so that the domain layer
 * physically cannot depend on the Android framework — the dependency rule from
 * ARCHITECTURE.md is enforced by the build, not by convention.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
    }
}
