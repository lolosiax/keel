package moe.lolosia.keel.idea.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import moe.lolosia.keel.idea.KeelPluginDiscovery

/**
 * 项目打开后把发现的插件工程同步成运行配置。
 *
 * 扫描放到后台线程；RunManager 的修改回到 EDT。
 */
class KeelProjectStartupActivity : StartupActivity {

    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val plugins = KeelPluginDiscovery.scan(project)
            if (plugins.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) KeelRunConfigurations.ensure(project, plugins)
            }
        }
    }
}
