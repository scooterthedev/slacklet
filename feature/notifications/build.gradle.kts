plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.scooter.slackwear.feature.notifications"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testApplicationId = "com.scooter.slackwear.feature.notifications.offlinetest"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))

    api(platform(libs.firebase.bom))
    api(libs.firebase.messaging)

    api(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
