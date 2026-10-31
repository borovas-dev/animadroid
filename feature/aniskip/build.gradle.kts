plugins { alias(libs.plugins.animadroid.android.library); alias(libs.plugins.animadroid.hilt); alias(libs.plugins.kotlin.serialization) }
dependencies { api(project(":core:model")); implementation(project(":core:network")); implementation(libs.kotlinx.serialization.json); testImplementation(libs.okhttp.mockwebserver) }
// KSP has no processors on the unit-test classpath ("No providers found"); skip those tasks.
tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach { enabled = false }
