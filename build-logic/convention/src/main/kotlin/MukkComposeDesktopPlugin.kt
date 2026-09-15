import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

class MukkComposeDesktopPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("mukk.kotlin.library")
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("org.jetbrains.compose.hot-reload")

            // Compose-specific detekt rules (unused remember, missing Modifier param, etc.) —
            // only this module actually writes @Composable code.
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies.add("detektPlugins", libs.findLibrary("composeRules-detekt").get())
        }
    }
}
