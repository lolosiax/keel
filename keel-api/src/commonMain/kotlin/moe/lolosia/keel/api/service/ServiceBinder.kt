package moe.lolosia.keel.api.service

/**
 * 生成代码注册本地服务的入口。
 *
 * 绑定同时登记两件事：本地实例（同模块内直接调用，零往返）与远端分发器
 * （其它插件经 Host 转发过来时由它执行）。
 */
interface ServiceBinder {

    fun <T : Any> bind(key: ServiceKey<T>, service: T, dispatcher: ServiceDispatcher)
}
