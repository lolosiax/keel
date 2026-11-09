package moe.lolosia.keel.idea.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project

class KeelRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = "KeelRun"

    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        KeelRunConfigurationOptions::class.java

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        KeelRunConfiguration(project, this, "Keel Plugin")
}
