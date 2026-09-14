plugins {
    id("mobileforge.android.application")
    id("mobileforge.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.mobileforge"

    defaultConfig {
        applicationId = "dev.mobileforge"
        versionCode = 2
        versionName = "0.2.0-phase2a"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // NO `applicationIdSuffix`, deliberately.
            //
            // Toolchain bundles bake an absolute prefix in at build time — library search
            // paths, interpreter paths, certificate locations (ADR-011). That prefix is
            // derived from the application id, so a `.debug` suffix would give debug builds
            // `/data/data/dev.mobileforge.debug/files/usr` and make every bundle built for
            // the real id unusable there. `ToolchainInstaller` would correctly refuse them.
            //
            // The alternative is building the entire toolchain twice, for three ABIs, which
            // costs hours per package. Sharing one application id costs the ability to
            // install debug and release side by side, which is worth far less.
            isMinifyEnabled = false
        }
        release {
            // Phase 1 has no release signing config yet; `assembleRelease` is not a
            // supported target until Phase 9. `assembleDebug` is the validated artifact.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            /*
             * Extract native libraries to real files on disk.
             *
             * Required, not a preference. Modern AGP defaults this to false, leaving .so files
             * inside the APK and mapping them directly — which works for System.loadLibrary but
             * NOT for LD_PRELOAD, because the dynamic linker needs a real path. Observed on
             * device as:
             *
             *   CANNOT LINK EXECUTABLE "/system/bin/sh": library ".../libmfexec.so" not found
             *
             * libmfexec is preloaded into every child process (ADR-011), so it must exist as a
             * file. The cost is a larger install footprint; the benefit is a toolchain that can
             * actually be invoked from a shell.
             */
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.security)
    implementation(projects.core.filesystem)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)

    implementation(projects.feature.projects)
    implementation(projects.feature.workspace)
    implementation(projects.feature.editor)
    implementation(projects.feature.settings)
    implementation(projects.feature.terminal)

    implementation(projects.runtime.api)

    // Phase 2: the real execution core replaces Phase 1's honest "not implemented" probe.
    implementation(projects.runtime.exec)

    // Phase 2b: the PTY, for a real interactive terminal (ADR-010).
    implementation(projects.runtime.pty)
    implementation(projects.runtime.toolchain)

    // Contract-only: present so the app can *detect and report* capability, not so it can
    // pretend the feature exists.
    implementation(projects.ai.api)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // The toolchain verification test parses a bundle manifest from test assets.
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
