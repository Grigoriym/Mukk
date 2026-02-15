import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.mukk.compose.desktop)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.player)
            implementation(projects.core.model)
            implementation(projects.core.scanner)
            implementation(projects.core.data)

            implementation(libs.kotlinx.collections)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.material.icons.extended)

            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}

// ./gradlew packageDeb
compose.desktop {
    application {
        mainClass = "com.grappim.mukk.MainKt"
        jvmArgs += listOf(
            "-Xmx256m",
            "-Xms64m",
            "-XX:+UseSerialGC",
            "-XX:MaxMetaspaceSize=128m",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "mukk"
            packageVersion = "1.0.0"
            description = "Music player"
            vendor = "Grappim"

            modules("java.sql", "java.naming", "jdk.unsupported")

            linux {
                iconFile.set(project.file("../info/art/logo.png"))
            }
        }
    }
}
