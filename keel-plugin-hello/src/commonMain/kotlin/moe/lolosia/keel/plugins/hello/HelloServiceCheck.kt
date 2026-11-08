package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.PluginContext

/**
 * 服务自检：走一次 Host 往返调用本插件声明的服务。
 *
 * 实现放在 nativeMain，因为跨模块调用依赖 C ABI 事件泵。
 */
internal expect fun checkHelloService(context: PluginContext)
