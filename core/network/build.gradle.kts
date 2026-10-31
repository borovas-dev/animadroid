plugins {
    alias(libs.plugins.animadroid.android.library)
    alias(libs.plugins.animadroid.android.compose)
    alias(libs.plugins.animadroid.hilt)
}
dependencies {
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.okhttp.mockwebserver)
}
