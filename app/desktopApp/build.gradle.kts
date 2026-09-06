import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":composeApp"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "dev.baylem.treasury.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Treasury"
            packageVersion = "1.0.0"
            modules("java.sql", "java.naming", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/treasury.ico"))
                menuGroup = "Treasury"
                upgradeUuid = "a37a4ac8-9c01-4eeb-9e15-b8835827e608"
                shortcut = true
                perUserInstall = true
            }
            macOS { iconFile.set(project.file("icons/treasury.icns")) }
            linux { iconFile.set(project.file("icons/treasury.png")) }
        }
    }
}
