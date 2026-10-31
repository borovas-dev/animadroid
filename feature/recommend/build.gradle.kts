plugins { alias(libs.plugins.animadroid.android.feature) }
dependencies {
    implementation(project(":provider:api"))
    implementation(project(":core:network"))
    implementation(libs.litertlm)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
