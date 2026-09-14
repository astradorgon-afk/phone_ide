import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig.targetSdk = libs.intVersion("targetSdk")

            testOptions.unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }

            lint {
                // ADR-007 deferred this to the end of Phase 2. Resolved as: NOT a blanket
                // switch, but specific issues promoted and specific ones silenced.
                //
                // A blanket `warningsAsErrors = true` fails on "a newer version exists", which
                // is time-dependent — the build would break spontaneously when an unrelated
                // library publishes a release, with no change to this repository. That trains
                // people to suppress warnings, which is the outcome the original decision was
                // trying to avoid.
                warningsAsErrors = false
                abortOnError = true
                checkDependencies = true

                // Promoted to errors: correctness issues that have actually bitten us.
                // Aligned16KB shipped unaligned native libraries for months — libmfexec is
                // LD_PRELOADed into every process, so on a 16 KB-page device nothing installed
                // could execute at all (RISK-004).
                error += listOf("Aligned16KB")

                // Silenced: advisory version nags. Dependency upgrades are a deliberate,
                // reviewed activity tracked in ROADMAP.md, not something to be prompted about
                // on every build by a check whose result changes without us touching anything.
                disable += listOf(
                    "GradleDependency",
                    "NewerVersionAvailable",
                    "AndroidGradlePluginVersion",
                )

                // Written on every build so CI and humans read the same report.
                htmlReport = true
                xmlReport = true
            }
        }
    }
}
