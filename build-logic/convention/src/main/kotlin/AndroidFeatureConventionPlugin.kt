import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("animadroid.android.library")
        pluginManager.apply("animadroid.android.compose")
        pluginManager.apply("animadroid.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:designsystem"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("navigation3-runtime"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
            add("implementation", libs.lib("coil-compose"))
        }
    }
}
