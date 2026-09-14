import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.kotlin.dsl.dependencies

/**
 * Applied on top of [AndroidApplicationConventionPlugin] or [AndroidLibraryConventionPlugin]
 * by modules that contain Compose UI. Kept separate so that pure-logic Android modules
 * (:core:filesystem, :core:data) never pay the Compose compiler cost.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // Kotlin 2.0 moved the Compose compiler into a first-party Kotlin plugin.
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val extension = extensions.findByType(CommonExtension::class.java)
            ?: error("mobileforge.android.compose requires an Android plugin to be applied first")

        extension.buildFeatures.compose = true

        val libs: VersionCatalog = libs
        dependencies {
            val bom = libs.findLibrary("androidx-compose-bom").get()
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))

            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())

            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
            add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
        }
    }
}
