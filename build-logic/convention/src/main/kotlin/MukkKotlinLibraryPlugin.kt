import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File

class MukkKotlinLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")

            extensions.configure<KotlinMultiplatformExtension>("kotlin") {
                jvm()
            }

            configureDetekt()
        }
    }
}

// One baseline file per module, so a fresh module starts clean instead of inheriting
// unrelated modules' pre-existing findings.
private fun Project.configureDetekt() {
    pluginManager.apply("dev.detekt")

    configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        allRules.set(false)
        parallel.set(true)
        config.setFrom(File(rootDir, "config/detekt/detekt.yml"))
        baseline.set(File(rootDir, "config/detekt/baseline/${path.trimStart(':').replace(":", "-")}.xml"))
        source.setFrom(layout.projectDirectory.dir("src"))
    }
}
