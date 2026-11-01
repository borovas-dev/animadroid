plugins {
    alias(libs.plugins.animadroid.android.application)
    alias(libs.plugins.animadroid.android.compose)
    alias(libs.plugins.animadroid.hilt)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.borovas.animadroid"
    defaultConfig {
        applicationId = "com.borovas.animadroid"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.borovas.animadroid.HiltTestRunner"
    }
    buildTypes {
        // ./gradlew -PqaSuffix=.qa lets parallel worktrees install side by side on a shared device.
        debug { (project.findProperty("qaSuffix") as? String)?.let { applicationIdSuffix = it } }
        // Release ships only the ABIs real phones use; ML Kit and LiteRT-LM native libs are large per ABI.
        // Debug keeps every ABI so emulators (x86_64) work.
        release {
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
dependencies {
    listOf("model","network","database","datastore","designsystem").forEach { implementation(project(":core:$it")) }
    implementation(project(":provider:api"))
    implementation(project(":provider:hianime"))
    implementation(project(":provider:shinden"))
    listOf("aniskip","search","details","player","library","downloads","settings","recommend").forEach { implementation(project(":feature:$it")) }
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.core.splashscreen); implementation(libs.androidx.activity.compose)
    implementation(libs.navigation3.runtime); implementation(libs.navigation3.ui); implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.compose.adaptive); implementation(libs.compose.adaptive.layout); implementation(libs.compose.adaptive.navigation3)
    implementation(libs.hilt.lifecycle.viewmodel.compose); implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.network.okhttp); implementation(libs.androidx.lifecycle.process)
    androidTestImplementation(libs.androidx.test.runner); androidTestImplementation(libs.androidx.test.rules); androidTestImplementation(libs.hilt.android.testing); kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext); androidTestImplementation(libs.compose.ui.test.junit4); debugImplementation(libs.compose.ui.test.manifest)
}
