plugins {
    alias(libs.plugins.mukk.kotlin.library)
}

kotlin {
    sourceSets{
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(projects.core.data)

            implementation(libs.compose.ui)
        }
        jvmMain.dependencies {
            implementation(libs.jaudiotagger)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}
