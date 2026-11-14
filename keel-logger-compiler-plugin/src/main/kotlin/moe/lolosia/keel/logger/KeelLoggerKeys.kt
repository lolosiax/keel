package moe.lolosia.keel.logger

import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** 编译器配置键；由 [KeelLoggerCliProcessor] 写入。 */
internal object KeelLoggerKeys {

    val ENABLED = CompilerConfigurationKey<Boolean>("keel-logger enabled")

    val WORKSPACE_ROOT = CompilerConfigurationKey<String>("keel-logger workspaceRoot")

    val SOURCE_ROOTS = CompilerConfigurationKey<List<String>>("keel-logger sourceRoots")

    val EXCEPTION_TRACE = CompilerConfigurationKey<Boolean>("keel-logger exceptionTrace")

    val DEBUG = CompilerConfigurationKey<Boolean>("keel-logger debug")

    val DEBUG_OUTPUT = CompilerConfigurationKey<String>("keel-logger debugOutput")

    val DEBUG_MODULE = CompilerConfigurationKey<String>("keel-logger debugModule")

    val DEBUG_BUILD_ID = CompilerConfigurationKey<String>("keel-logger debugBuildId")
}
