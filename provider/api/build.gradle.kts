plugins { alias(libs.plugins.animadroid.jvm.library) }
dependencies { api(project(":core:model")); api(libs.kotlinx.coroutines.core) }
