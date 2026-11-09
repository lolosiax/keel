package moe.lolosia.keel.idea.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import moe.lolosia.keel.idea.KeelPluginDiscovery

/**
 * 从当前文件定位所属插件工程，生成 Keel 运行配置。
 *
 * 任何位于插件工程内的文件都能触发；这比只认 `@KeelPluginMain`
 * 更宽松，也保留了在资源 / 配置文件中直接点 Run 的能力。
 */
class KeelRunConfigurationProducer : LazyRunConfigurationProducer<KeelRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(KeelRunConfigurationType::class.java)
            .configurationFactories
            .single()

    override fun setupConfigurationFromContext(
        configuration: KeelRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.psiLocation?.containingFile?.virtualFile ?: return false
        val plugin = KeelPluginDiscovery.findPluginProject(file) ?: return false
        configuration.pluginProjectDir = plugin.directory.toString()
        configuration.name = "Keel: ${plugin.displayName}"
        configuration.ensureBuildTask()
        return true
    }

    override fun isConfigurationFromContext(
        configuration: KeelRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.psiLocation?.containingFile?.virtualFile ?: return false
        val plugin = KeelPluginDiscovery.findPluginProject(file) ?: return false
        return configuration.pluginProjectDir == plugin.directory.toString()
    }
}
