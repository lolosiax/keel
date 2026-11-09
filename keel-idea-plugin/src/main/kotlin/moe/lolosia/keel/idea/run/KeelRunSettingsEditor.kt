package moe.lolosia.keel.idea.run

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import moe.lolosia.keel.idea.KeelPluginDiscovery
import moe.lolosia.keel.idea.KeelPluginProject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Keel 运行配置表单。
 *
 * 插件工程列表来自工作区静态扫描；从代码里创建配置时 producer 也会直接填好路径。
 */
class KeelRunSettingsEditor(private val project: Project) : SettingsEditor<KeelRunConfiguration>() {

    private val projects = ComboBox<KeelPluginProject>()
    private val refresh = JButton("刷新").apply {
        addActionListener { refreshProjects() }
    }
    private val browse = JButton("浏览...").apply {
        addActionListener { browseProject() }
    }
    private val projectButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        add(refresh)
        add(browse)
    }
    private val projectPanel = JPanel(BorderLayout(4, 0)).apply {
        add(projects, BorderLayout.CENTER)
        add(projectButtons, BorderLayout.EAST)
    }

    private val workDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
    }
    private val port = JSpinner(SpinnerNumberModel(7191, 1, 65535, 1))
    private val buildBeforeRun = JBCheckBox("运行前执行 keelPrepareRun（输出到 Build 面板）")
    private val arguments = JBTextField()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("插件工程:", projectPanel)
        .addLabeledComponent("运行目录:", workDirectory)
        .addLabeledComponent("HTTP 端口:", port)
        .addComponent(buildBeforeRun)
        .addLabeledComponent("启动参数:", arguments)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun resetEditorFrom(configuration: KeelRunConfiguration) {
        refreshProjects()
        select(configuration.pluginProjectDir)
        workDirectory.text = configuration.workDir.orEmpty()
        port.value = configuration.port
        buildBeforeRun.isSelected = configuration.buildBeforeRun
        arguments.text = configuration.programArguments
    }

    override fun applyEditorTo(configuration: KeelRunConfiguration) {
        configuration.pluginProjectDir = (projects.selectedItem as? KeelPluginProject)
            ?.directory
            ?.toString()
        configuration.workDir = workDirectory.text.trim().takeIf { it.isNotEmpty() }
        configuration.port = (port.value as Number).toInt()
        configuration.buildBeforeRun = buildBeforeRun.isSelected
        configuration.programArguments = arguments.text
        configuration.ensureBuildTask()
    }

    override fun createEditor(): JComponent = panel

    private fun refreshProjects() {
        val selected = (projects.selectedItem as? KeelPluginProject)?.directory?.toString()
        projects.removeAllItems()
        val discovered = KeelPluginDiscovery.scan(project)
        discovered.forEach(projects::addItem)
        // 打开表单或点刷新时同步运行配置列表，避免新增插件工程还要重启 IDE
        KeelRunConfigurations.ensure(project, discovered)
        select(selected)
    }

    private fun browseProject() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val directory = File(file.path)
        val item = KeelPluginProject(directory.toPath(), directory.name, directory.name)
        projects.addItem(item)
        projects.selectedItem = item
    }

    private fun select(path: String?) {
        if (path.isNullOrBlank()) return
        for (index in 0 until projects.itemCount) {
            val item = projects.getItemAt(index)
            if (item.directory.toString() == path) {
                projects.selectedIndex = index
                return
            }
        }
        // 配置可能来自 producer 或手写 XML；列表里没有时补一个占位项
        val directory = File(path)
        val item = KeelPluginProject(directory.toPath(), directory.name, directory.name)
        projects.addItem(item)
        projects.selectedItem = item
    }
}
