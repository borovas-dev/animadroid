plugins {
    alias(libs.plugins.animadroid.android.library)
    alias(libs.plugins.animadroid.hilt)
    alias(libs.plugins.room3)
    alias(libs.plugins.kotlin.serialization)
}
room3 { schemaDirectory("$projectDir/schemas") }
dependencies {
    api(project(":core:model"))
    api(libs.room3.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room3.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.room3.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext)
}
// Robolectric on very new JDKs needs access to JDK internals.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
// Exported Room schemas are served as test assets so MigrationTestHelper can find them under Robolectric.
android { sourceSets.getByName("test").assets.directories.add("$projectDir/schemas") }
// No annotation processors in unit tests; KSP errors out with an empty processor classpath.
