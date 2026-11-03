plugins {
    alias(libs.plugins.animadroid.android.library)
    alias(libs.plugins.animadroid.hilt)
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:network"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}
