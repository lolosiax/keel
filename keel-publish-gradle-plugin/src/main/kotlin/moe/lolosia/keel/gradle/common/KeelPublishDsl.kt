package moe.lolosia.keel.gradle.common

import moe.lolosia.keel.gradle.publish.KeelPublishExtension
import org.gradle.api.Action

/** `keel { publish { ... } }`；只有应用了 `moe.lolosia.keel.publish` 才存在。 */
val KeelExtension.publish: KeelPublishExtension
    get() = get(KeelPublishExtension::class.java)

fun KeelExtension.publish(action: Action<KeelPublishExtension>) = action.execute(publish)
