package moe.lolosia.keel.abi

/**
 * ABI 版本号，与同目录 C 头文件的 `KEEL_ABI_VERSION` 由同一次构建产出。
 *
 * Host 校验 `plugin.json.abiVersion` 时用这个常量，避免 Kotlin 侧再写一个魔法数字。
 */
object KeelAbi {

    const val VERSION: Int = 1
}
