package moe.lolosia.keel.api.event

/**
 * 事件基类。
 *
 * 事件只在**产生它的运行时**里构造；跨模块投递时会被投影成「事件类型 id + 载荷字节」，
 * 由接收侧重建本地实例。不要把事件对象本身交给另一个运行时。
 */
open class Event
