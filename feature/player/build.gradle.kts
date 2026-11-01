plugins { alias(libs.plugins.animadroid.android.feature) }
dependencies {
    implementation(project(":feature:aniskip"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:subtitles"))
    implementation(project(":provider:api"))
    implementation(project(":core:network"))
    implementation(libs.androidx.activity.compose); implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer); implementation(libs.media3.exoplayer.hls); implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session); implementation(libs.media3.ui); implementation(libs.media3.ui.compose); implementation(libs.media3.ui.compose.material3)
}
// KSP has no processors on the unit-test classpath ("No providers found"); skip those tasks.
