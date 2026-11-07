package moe.lolosia.keel.plugins.hello

/**
 * 故意触发一次随机的原生内存故障，用于验证 Host 的崩溃归因与看门狗报告。
 *
 * 只做演示：正常业务代码不应调用。
 */
internal expect fun crashRandomly(): Nothing
