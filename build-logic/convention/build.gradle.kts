plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(libs.kotlin.multiplatform.gradle.plugin)
    implementation(libs.compose.multiplatform.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.compose.hot.reload.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("mukkKotlinLibrary") {
            id = "mukk.kotlin.library"
            implementationClass = "MukkKotlinLibraryPlugin"
        }
        register("mukkComposeDesktop") {
            id = "mukk.compose.desktop"
            implementationClass = "MukkComposeDesktopPlugin"
        }
    }
}
