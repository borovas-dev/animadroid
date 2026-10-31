plugins { alias(libs.plugins.animadroid.android.library); alias(libs.plugins.animadroid.hilt) }
dependencies { api(project(":core:model")); api(libs.datastore.preferences) }
