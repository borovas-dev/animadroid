plugins {
    alias(libs.plugins.animadroid.android.library)
    alias(libs.plugins.animadroid.hilt)
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":core:model"))
    api(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
// No annotation processors in unit tests; KSP errors out with an empty processor classpath.
tasks.configureEach { if (name.startsWith("ksp") && name.endsWith("UnitTestKotlin")) enabled = false }
