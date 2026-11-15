package moe.lolosia.keel.host

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import moe.lolosia.keel.classpath.embeddedAppId
import moe.lolosia.keel.classpath.embeddedUserDataDefault
import moe.lolosia.keel.host.plugin.PluginFs
import moe.lolosia.keel.host.plugin.mkdirs
import moe.lolosia.keel.host.plugin.userCacheRoot
import moe.lolosia.keel.host.plugin.userDataRoot
import platform.posix.getenv

/**
 * 解析启动目录：默认程序目录，`KEEL_DATA_HOME` / `KEEL_CACHE_HOME` 可分别重定向。
 *
 * 单 exe 可以在构建期用 `keelPlugin.executable.dataMode = KeelDataMode.USERDATA` 把默认值
 * 改成用户目录；运行期环境变量仍然优先。`KEEL_HOME` 仍被当作 `KEEL_DATA_HOME` 的别名，
 * 兼容按「安装根」部署的旧配置。
 */
internal actual fun resolveHostDirs(pluginsDir: String?, cacheDir: String?): HostDirs {
    // 单 exe 优先用构建期写入的 appId：产物改名不会把用户数据目录带跑
    val appId = (if (pluginsDir == null) sanitizeAppId(embeddedAppId(executablePath())) else null)
        ?: executableAppId()
    // `--plugins` 模式以启动工作目录为程序目录（keelRun 的 build/keel-run）；
    // 单文件模式没有可靠的 cwd（双击启动时由系统决定），用可执行文件所在目录。
    val programDir = if (pluginsDir != null) normalize(currentWorkingDirectory()) ?: "."
    else executableDirectory()
    val buildUserData = pluginsDir == null && embeddedUserDataDefault(executablePath())

    val explicitData = env("KEEL_DATA_HOME") ?: env("KEEL_HOME")
    var usedUserData = false
    val dataRoot = when {
        explicitData != null && writable(explicitData) -> explicitData
        // 构建期的 dataMode = USERDATA：优先用户目录，取不到再退回程序目录
        buildUserData -> userDataRoot(appId)?.takeIf { writable(it) }?.also { usedUserData = true }
            ?: programDir
        writable(programDir) -> programDir
        // 程序目录不可写（Program Files / 只读安装）：自动回退用户目录
        else -> userDataRoot(appId)?.takeIf { writable(it) }?.also { usedUserData = true }
            ?: programDir
    }

    val explicitCache = env("KEEL_CACHE_HOME") ?: normalize(cacheDir)
    val cacheRoot = listOfNotNull(
        explicitCache,
        if (usedUserData) userCacheRoot(appId) else null,
        "$dataRoot/.cache",
        userCacheRoot(appId),
    ).firstOrNull { writable(it) } ?: "$dataRoot/.cache"

    return HostDirs(dataRoot, cacheRoot)
}

/** 构建期 appId 兜底清洗：只保留单段目录名，非法字符替换为 `_`。 */
private fun sanitizeAppId(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: return null
    val cleaned = trimmed.map { char ->
        if (char == '/' || char == '\\' || char == ':' || char == '*' || char == '?' ||
            char == '"' || char == '<' || char == '>' || char == '|' || char.isISOControl()
        ) '_' else char
    }.joinToString("")
    return cleaned.ifEmpty { null }
}

/** 可执行文件名（去掉扩展名）作为用户目录下的 app id 兜底。 */
private fun executableAppId(): String {
    val stem = executablePath().replace('\\', '/')
        .substringAfterLast('/')
        .substringBeforeLast('.')
    val cleaned = stem.map { char ->
        if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') char else '_'
    }.joinToString("")
    return cleaned.ifBlank { "keel" }
}

private fun executableDirectory(): String =
    executablePath().replace('\\', '/').substringBeforeLast('/', ".")

/** 去掉尾部斜杠；空串视为未配置。 */
private fun normalize(path: String?): String? =
    path?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? =
    getenv(name)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }

/** 建目录并写一个探测文件；不可写时返回 false。 */
private fun writable(dir: String): Boolean {
    mkdirs(dir)
    val probe = "${dir.trimEnd('/', '\\')}/.keel-write-test"
    return try {
        PluginFs.writeBytes(probe, ByteArray(0))
        PluginFs.delete(probe)
    } catch (_: Throwable) {
        false
    }
}
