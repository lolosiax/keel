package moe.lolosia.keel.api.config

import kotlinx.serialization.serializer

inline fun <reified T> Config.read(path: String = ""): T = read(serializer<T>(), path)

inline fun <reified T> Config.write(value: T, path: String = "") = write(serializer<T>(), value, path)
