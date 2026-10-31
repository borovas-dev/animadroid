plugins { alias(libs.plugins.animadroid.android.feature) }
dependencies {
    implementation(project(":provider:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Media3 types (CacheDataSource.Factory) are part of the public API consumed by :feature:player.
    api(libs.media3.exoplayer)
    api(libs.media3.exoplayer.hls)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext)
}
// Robolectric on very new JDKs needs access to JDK internals.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
