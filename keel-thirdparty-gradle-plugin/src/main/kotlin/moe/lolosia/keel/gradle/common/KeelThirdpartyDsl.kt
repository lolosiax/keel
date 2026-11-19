package moe.lolosia.keel.gradle.common

import moe.lolosia.keel.gradle.thirdparty.KeelThirdpartyExtension
import org.gradle.api.Action

/** `keel { thirdparty { ... } }`；只有应用了 `moe.lolosia.keel.thirdparty` 才存在。 */
val KeelExtension.thirdparty: KeelThirdpartyExtension
    get() = get(KeelThirdpartyExtension::class.java)

fun KeelExtension.thirdparty(action: Action<KeelThirdpartyExtension>) = action.execute(thirdparty)
