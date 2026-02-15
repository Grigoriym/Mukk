plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)

            implementation(libs.kotlinx.collections)
        }
        jvmMain.dependencies {
            implementation(libs.exposed.core)
            implementation(libs.exposed.dao)
            implementation(libs.exposed.jdbc)
            implementation(libs.sqlite.jdbc)
        }
    }
}
