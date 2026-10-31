import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        dependencies {
            add("implementation", libs.lib("hilt-android"))
            add("ksp", libs.lib("hilt-compiler"))
        }
        // Hilt shades an older kotlin-metadata-jvm; force one that reads the current Kotlin metadata.
        val kv = libs.findVersion("kotlin").get().requiredVersion
        configurations.configureEach {
            if (name.startsWith("ksp") || name.contains("AnnotationProcessor", true) || name.startsWith("hilt")) {
                resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:$kv")
                dependencies.add(project.dependencies.create("org.jetbrains.kotlin:kotlin-metadata-jvm:$kv"))
            }
        }
    }
}
