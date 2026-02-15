plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

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
