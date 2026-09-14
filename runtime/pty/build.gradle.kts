plugins {
    id("mobileforge.android.library")
}

android {
    namespace = "dev.mobileforge.runtime.pty"

    defaultConfig {
        externalNativeBuild {
            cmake {
                // Only the ABIs we can actually ship and test. armeabi-v7a is included because
                // a 32-bit device still needs a working terminal; x86_64 is here so the
                // emulator can run the verification tests.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(projects.runtime.api)
    implementation(projects.core.common)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
