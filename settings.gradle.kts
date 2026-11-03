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
    ":provider:api", ":provider:common", ":provider:hianime", ":provider:shinden", ":provider:animefire",
    ":provider:anilibria",
    ":feature:aniskip", ":feature:search", ":feature:details", ":feature:player",
    ":feature:library", ":feature:downloads", ":feature:settings", ":feature:recommend", ":feature:subtitles", ":feature:dubbing",
    ":core:remoteconfig", ":core:sync",
    ":provider:animeparadise",
    ":provider:kickassanime",
)
