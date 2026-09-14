import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)

            // Library modules do not declare targetSdk; it is an application-level concern.
            defaultConfig.consumerProguardFiles("consumer-rules.pro")

            testOptions.unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }

            lint {
                // See AndroidApplicationConventionPlugin for the reasoning: specific issues are
                // promoted or silenced rather than flipping warningsAsErrors wholesale.
                warningsAsErrors = false
                abortOnError = true

                // 16 KB alignment is a library-module concern first — :runtime:pty is where the
                // native code that shipped unaligned actually lives (RISK-004).
                error += listOf("Aligned16KB")

                disable += listOf(
                    "GradleDependency",
                    "NewerVersionAvailable",
                    "AndroidGradlePluginVersion",
                )
            }
        }
    }
}
