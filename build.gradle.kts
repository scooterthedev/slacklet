plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kover)
}

dependencies {
    listOf(
        ":core:model", ":core:data", ":core:network", ":core:database",
        ":feature:home", ":feature:conversation", ":feature:notifications", ":feature:search",
        ":relay",
    ).forEach { kover(project(it)) }
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("androidx.compose.runtime.Composable")
                classes(
                    "*_Impl",
                    "*_Impl\$*",
                    "*ComposableSingletons*",
                    "*\$\$serializer",
                    "*_Factory",
                    "*BuildConfig",
                )
            }
        }
    }
}
