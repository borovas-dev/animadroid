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
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "com.borovas.animadroid.HiltTestRunner"
        // Tracker OAuth client ids (no secrets): -PANILIST_CLIENT_ID=... -PMAL_CLIENT_ID=... or ~/.gradle/gradle.properties. Blank = sync disabled.
        listOf("ANILIST_CLIENT_ID", "MAL_CLIENT_ID").forEach { buildConfigField("String", it, "\"${(project.findProperty(it) as? String).orEmpty()}\"") }
    }
    // Only the UI languages we translate; keeps library locales (and the per-app language list) in sync with locales_config.xml.
    buildFeatures { buildConfig = true }
    androidResources { localeFilters += listOf("en", "pl", "zh") }
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
    implementation(project(":provider:anilibria"))
    implementation(project(":provider:hianime"))
    implementation(project(":provider:shinden"))
    implementation(project(":provider:animefire"))
    implementation(project(":provider:animeparadise"))
    implementation(project(":provider:kickassanime"))
    implementation(project(":core:remoteconfig"))
    implementation(project(":core:sync"))
    listOf("aniskip","search","details","player","library","downloads","settings","recommend","dubbing").forEach { implementation(project(":feature:$it")) }
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.core.splashscreen); implementation(libs.androidx.activity.compose)
    implementation(libs.navigation3.runtime); implementation(libs.navigation3.ui); implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.compose.adaptive); implementation(libs.compose.adaptive.layout); implementation(libs.compose.adaptive.navigation3)
    implementation(libs.hilt.lifecycle.viewmodel.compose); implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.network.okhttp); implementation(libs.androidx.lifecycle.process)
    androidTestImplementation(libs.androidx.test.runner); androidTestImplementation(libs.androidx.test.rules); androidTestImplementation(libs.hilt.android.testing); kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext); androidTestImplementation(libs.compose.ui.test.junit4); debugImplementation(libs.compose.ui.test.manifest)
}
