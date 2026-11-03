plugins { alias(libs.plugins.animadroid.android.library) }
dependencies {
    api(project(":provider:api")); api(project(":core:network"))
    implementation(libs.jsoup); implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver); testImplementation(libs.kotlinx.coroutines.test)
}
