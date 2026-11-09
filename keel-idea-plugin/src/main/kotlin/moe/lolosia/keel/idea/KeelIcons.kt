package moe.lolosia.keel.idea

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 插件图标入口。
 *
 * 16px 运行配置图标用实心版本；40px 插件列表图标放在
 * `META-INF/pluginIcon.svg`，用空心版本。
 */
object KeelIcons {
    val RunConfiguration: Icon = IconLoader.getIcon("/icons/keel-16.svg", KeelIcons::class.java)
}
