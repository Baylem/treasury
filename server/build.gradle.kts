plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

version = "1.0.0"
application {
    mainClass = "dev.baylem.treasury.ApplicationKt"
}

kotlin { jvmToolchain(21) }

tasks.test {
    inputs.property("integrationDatabase", providers.environmentVariable("TREASURY_TEST_DATABASE_URL").orElse("h2"))
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    val ktorVersion = libs.versions.ktor.get()
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-body-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("org.jetbrains.exposed:exposed-core:1.5.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.5.0")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.flywaydb:flyway-core:11.15.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.15.0")
    implementation("de.mkammerer:argon2-jvm:2.12")
    implementation("org.eclipse.angus:jakarta.mail:2.0.4")
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    // 2.4.240 has an open-session CHECK regression: h2database/h2database#4291.
    testImplementation("com.h2database:h2:2.3.232")
}
