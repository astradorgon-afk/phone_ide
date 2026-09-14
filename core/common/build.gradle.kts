plugins {
    id("mobileforge.jvm.library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
