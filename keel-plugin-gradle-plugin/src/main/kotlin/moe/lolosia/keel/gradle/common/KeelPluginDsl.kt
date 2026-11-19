package moe.lolosia.keel.gradle.common

import moe.lolosia.keel.gradle.plugin.KeelPluginExtension
import org.gradle.api.Action

/** `keel { plugin { ... } }`；只有应用了 `moe.lolosia.keel.plugin` 才存在。 */
val KeelExtension.plugin: KeelPluginExtension
    get() = get(KeelPluginExtension::class.java)

fun KeelExtension.plugin(action: Action<KeelPluginExtension>) = action.execute(plugin)
