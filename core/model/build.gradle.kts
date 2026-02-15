plugins {
    alias(libs.plugins.mukk.kotlin.library)
}

kotlin {
    sourceSets{
        commonMain.dependencies {
            implementation(libs.kotlinx.collections)
        }
    }
}
