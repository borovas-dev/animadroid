plugins {
    alias(libs.plugins.animadroid.android.library)
    alias(libs.plugins.animadroid.hilt)
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime)
    testImplementation(libs.okhttp.mockwebserver)
}
