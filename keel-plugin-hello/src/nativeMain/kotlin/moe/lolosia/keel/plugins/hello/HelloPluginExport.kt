package moe.lolosia.keel.plugins.hello

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef

/**
 * 插件唯一对外符号：返回 `StableRef` 包装的 [HelloPlugin] 句柄。
 *
 * ⚠️ 只能在**插件自己的 Worker 线程**上调用（由 C 侧的 bootstrap 线程首次进入运行时），
 * 不能从 Host 的 Kotlin 线程直接调用 —— 否则同一个线程会先后进入两个 Kotlin/Native 运行时。
 * 宿主只持有该不透明指针，所有生命周期调用都经 `keel-api` 定义的 C ABI 完成；
 * 宿主释放插件时必须回调 dispose（协议见 doc/abi-design.md）。
 */
@OptIn(ExperimentalNativeApi::class)
@CName("keel_plugin_hello_create")
fun createHelloPlugin(): COpaquePointer = StableRef.create(HelloPlugin()).asCPointer()
