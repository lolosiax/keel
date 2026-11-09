package moe.lolosia.keel.idea.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import moe.lolosia.keel.idea.KeelIcons

/**
 * Run Configuration 类型入口；工厂由 [ConfigurationTypeBase] 统一登记。
 */
class KeelRunConfigurationType : ConfigurationTypeBase(
    "KeelRunConfiguration",
    "Keel Plugin",
    "运行一个 Keel 插件工程（构建与运行分离）",
    KeelIcons.RunConfiguration,
) {
    init {
        addFactory(KeelRunConfigurationFactory(this))
    }
}
