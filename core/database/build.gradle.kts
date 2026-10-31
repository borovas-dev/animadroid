plugins { alias(libs.plugins.animadroid.android.library); alias(libs.plugins.animadroid.hilt); alias(libs.plugins.room3) }
room3 { schemaDirectory("$projectDir/schemas") }
dependencies {
    api(project(":core:model")); api(libs.room3.runtime); implementation(libs.sqlite.bundled); ksp(libs.room3.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.room3.testing); testImplementation(libs.robolectric)
}
