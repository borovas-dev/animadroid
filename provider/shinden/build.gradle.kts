plugins { alias(libs.plugins.animadroid.android.library); alias(libs.plugins.animadroid.hilt); alias(libs.plugins.kotlin.serialization) }
dependencies {
    api(project(":provider:api")); implementation(project(":core:network"))
    implementation(libs.jsoup); implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
}
