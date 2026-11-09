package moe.lolosia.keel.idea.run

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.project.Project
import moe.lolosia.keel.idea.KeelPluginProject

/**
 * 把扫描到的插件工程同步成运行配置。
 *
 * 以插件工程目录去重：用户改过名字、调过端口都不会重复创建；
 * 用户删掉的配置会在下次项目打开 / 刷新时重新出现。
 */
object KeelRunConfigurations {

    fun ensure(project: Project, plugins: List<KeelPluginProject>) {
        if (plugins.isEmpty()) return

        val runManager = RunManager.getInstance(project)
        val factory = ConfigurationTypeUtil.findConfigurationType(KeelRunConfigurationType::class.java)
            .configurationFactories
            .single()
        val known = runManager.allSettings
            .mapNotNull { it.configuration as? KeelRunConfiguration }
            .mapNotNull { it.pluginProjectDir }
            .toMutableSet()

        // 已有配置也补一次 before-run task（旧版本创建的配置没有这个任务）
        runManager.allSettings
            .mapNotNull { it.configuration as? KeelRunConfiguration }
            .forEach { it.ensureBuildTask() }

        plugins.forEach { plugin ->
            val path = plugin.directory.toString()
            if (!known.add(path)) return@forEach

            val baseName = "Keel: ${plugin.displayName}"
            val name = if (runManager.allSettings.any { it.name == baseName }) {
                "$baseName (${plugin.pluginId})"
            } else {
                baseName
            }
            val settings = runManager.createConfiguration(name, factory)
            val configuration = settings.configuration as KeelRunConfiguration
            configuration.pluginProjectDir = path
            configuration.ensureBuildTask()
            settings.isTemporary = false
            runManager.addConfiguration(settings)
        }
    }
}
