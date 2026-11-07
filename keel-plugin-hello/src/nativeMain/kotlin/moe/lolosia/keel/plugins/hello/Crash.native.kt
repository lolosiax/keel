package moe.lolosia.keel.plugins.hello

import kotlinx.cinterop.*
import moe.lolosia.keel.api.Log
import platform.posix.malloc

/** 让指针跑出堆区，必然落到未映射页；用常量保证不会被优化掉。 */
private const val FAR_OFFSET = 0x20000000

/**
 * 三种原生内存故障，随机选一种：
 *
 * 1. 高地址写 —— 越界写到堆区之外；
 * 2. 高地址读 —— 与 Windows 的「该内存不能为 read」同类；
 * 3. 低地址写 —— 往映射区下方写。
 *
 * 故障指令就在插件自己的代码段里（不是 libc 内部），所以崩溃报告能把 PC
 * 归因到 `hello`；故障发生在当前 Worker 线程，由 Host 的信号 / 异常处理器接管。
 */
internal actual fun crashRandomly(): Nothing {
    val mode = (0..2).random()
    Log.warn(
        "/crash: 内存故障模式 = " + when (mode) {
            0 -> "高地址写"
            1 -> "高地址读"
            else -> "低地址写"
        },
    )

    val block = (malloc(64uL) ?: error("malloc 失败")).reinterpret<ByteVar>()
    val above = (block + FAR_OFFSET) ?: error("指针运算失败")
    val below = (block + -FAR_OFFSET) ?: error("指针运算失败")
    when (mode) {
        0 -> above[0] = 0x42.toByte()
        1 -> {
            @Suppress("UNUSED_VARIABLE")
            val value = above[0]
        }
        else -> below[0] = 0x42.toByte()
    }
    error("内存故障没有触发：mode=$mode")
}
