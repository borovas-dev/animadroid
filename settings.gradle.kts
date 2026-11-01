pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "animadroid"
include(
    ":app",
    ":core:model", ":core:network", ":core:database", ":core:datastore", ":core:designsystem",
    ":provider:api", ":provider:hianime", ":provider:shinden",
    ":feature:aniskip", ":feature:search", ":feature:details", ":feature:player",
    ":feature:library", ":feature:downloads", ":feature:settings", ":feature:recommend",
)
