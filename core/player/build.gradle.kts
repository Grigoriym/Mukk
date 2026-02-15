plugins {
    alias(libs.plugins.mukk.kotlin.library)
}

kotlin {
    sourceSets{
        commonMain.dependencies {
            implementation(projects.core.model)
        }
        jvmMain.dependencies {
            implementation(libs.gst1.java.core)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}
