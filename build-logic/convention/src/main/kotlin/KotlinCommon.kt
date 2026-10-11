import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Compiler flags applied to every module.
 *
 * `-Xjvm-default=all` is required so that interfaces in :core and :runtime:api can carry
 * default implementations without forcing every Java consumer to reimplement them.
 * Warnings are intentionally NOT errors yet: Phase 1 has deliberately-unfinished seams
 * (see ROADMAP.md) and failing the build on a deprecation warning would push contributors
 * toward suppressing rather than fixing. This is revisited at the end of Phase 2.
 */
private val COMMON_COMPILER_ARGS = listOf(
    "-Xjvm-default=all",
    "-opt-in=kotlin.RequiresOptIn",
)

internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = libs.intVersion("compileSdk")

        // Pinned, not defaulted. AGP silently installs and uses its own default NDK (r27 at
        // AGP 8.13) when nothing is pinned — observed on a fresh machine where a staged r29
        // sat unused. The 16 KB-page story (RISK-004, ADR-011) was verified against r29-built
        // libraries, so the build must say r29 out loud rather than accept whatever AGP picks.
        ndkVersion = "29.0.13113456"

        defaultConfig {
            minSdk = libs.intVersion("minSdk")
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        // Duplicated META-INF from test/coroutines artifacts otherwise breaks packaging.
        packaging {
            resources.excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
            )
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(COMMON_COMPILER_ARGS)
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(COMMON_COMPILER_ARGS)
        }
    }
}
