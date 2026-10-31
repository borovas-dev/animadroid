plugins { alias(libs.plugins.animadroid.android.feature) }
dependencies {
    implementation(project(":provider:api"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
