import org.gradle.api.Plugin
import org.gradle.api.Project

class MukkComposeDesktopPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("mukk.kotlin.library")
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("org.jetbrains.compose.hot-reload")
        }
    }
}
