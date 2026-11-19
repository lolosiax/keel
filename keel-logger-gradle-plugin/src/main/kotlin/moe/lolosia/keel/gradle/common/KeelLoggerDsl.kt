package moe.lolosia.keel.gradle.common

import moe.lolosia.keel.gradle.logger.KeelLoggerExtension
import org.gradle.api.Action

/** `keel { logger { ... } }`；只有应用了 `moe.lolosia.keel.logger` 才存在。 */
val KeelExtension.logger: KeelLoggerExtension
    get() = get(KeelLoggerExtension::class.java)

fun KeelExtension.logger(action: Action<KeelLoggerExtension>) = action.execute(logger)
