package moe.lolosia.keel.logger

import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** 编译器配置键；由 [KeelLoggerCliProcessor] 写入。 */
internal object KeelLoggerKeys {

    val ENABLED = CompilerConfigurationKey<Boolean>("keel-logger enabled")

    val WORKSPACE_ROOT = CompilerConfigurationKey<String>("keel-logger workspaceRoot")

    val SOURCE_ROOTS = CompilerConfigurationKey<List<String>>("keel-logger sourceRoots")
}
