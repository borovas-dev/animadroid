plugins { alias(libs.plugins.animadroid.android.feature) }
dependencies {
    implementation(project(":feature:aniskip"))
    implementation(libs.media3.exoplayer); implementation(libs.media3.exoplayer.hls); implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session); implementation(libs.media3.ui.compose); implementation(libs.media3.ui.compose.material3)
}
