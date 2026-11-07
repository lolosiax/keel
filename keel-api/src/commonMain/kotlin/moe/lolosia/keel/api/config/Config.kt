package moe.lolosia.keel.api.config

import kotlinx.serialization.*

/**
 * 一份 YAML 配置的读写入口。
 *
 * - 读取：用 kotlinx.serialization 的 serializer 绑定类型，**不依赖反射**；
 * - 写入：按路径合并，保留原文件里的注释与排版（实现见 keel-host）；
 * - 每个插件有自己的一份文件，Host 的 `application.yaml` 是另一份。
 *
 * 缺失的路径：`T` 可空时返回 null，否则抛 [NoSuchElementException]。
 */
interface Config {

    fun <T> read(serializer: KSerializer<T>, path: String = ""): T

    /**
     * 把 [value] 合并到 [path] 指向的节点。
     *
     * 实现会做逐字段 diff：只改变化的叶子，未改动的键、注释、空行保持原样。
     * 列表与对象整体替换时，块内部的自定义注释无法保留。
     */
    fun <T> write(serializer: KSerializer<T>, value: T, path: String = "")

    /** 落盘；Host 可能合并短时间内的多次写。 */
    fun save()

    /** 丢弃内存态，重新从磁盘加载。 */
    fun reload()
}
