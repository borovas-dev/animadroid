plugins { alias(libs.plugins.animadroid.android.feature) }
dependencies {
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.translate)
}
