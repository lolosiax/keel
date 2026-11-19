package moe.lolosia.keel.gradle.common

import moe.lolosia.keel.gradle.abi.KeelAbiExtension
import org.gradle.api.Action

/** `keel { abi { ... } }`；只有应用了 `moe.lolosia.keel.abi` 才存在。 */
val KeelExtension.abi: KeelAbiExtension
    get() = get(KeelAbiExtension::class.java)

fun KeelExtension.abi(action: Action<KeelAbiExtension>) = action.execute(abi)
