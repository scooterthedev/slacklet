plugins {

    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.shadow)
    application
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.scooter.slackwear.relay.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.firebase.admin)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "com.scooter.slackwear.relay.ApplicationKt"
    }
}
