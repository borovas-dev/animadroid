plugins { `kotlin-dsl` }
group = "com.borovas.animadroid.buildlogic"
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}
gradlePlugin {
    plugins {
        register("app") { id = "animadroid.android.application"; implementationClass = "AndroidApplicationConventionPlugin" }
        register("lib") { id = "animadroid.android.library"; implementationClass = "AndroidLibraryConventionPlugin" }
        register("compose") { id = "animadroid.android.compose"; implementationClass = "AndroidComposeConventionPlugin" }
        register("feature") { id = "animadroid.android.feature"; implementationClass = "AndroidFeatureConventionPlugin" }
        register("hilt") { id = "animadroid.hilt"; implementationClass = "HiltConventionPlugin" }
        register("jvm") { id = "animadroid.jvm.library"; implementationClass = "JvmLibraryConventionPlugin" }
    }
}
