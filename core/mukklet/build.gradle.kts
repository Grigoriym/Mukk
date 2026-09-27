plugins {
    alias(libs.plugins.mukk.kotlin.library)
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.core.model)

            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
