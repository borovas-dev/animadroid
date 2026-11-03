plugins { alias(libs.plugins.animadroid.android.feature) }
dependencies {
    implementation(project(":feature:subtitles"))
    implementation(project(":provider:api"))
    implementation(project(":core:sync"))
    implementation(libs.androidx.browser)
}
