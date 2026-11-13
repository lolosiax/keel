#ifndef KEEL_ZSTD_H
#define KEEL_ZSTD_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * zstd 解压入口（实现见 zstddeclib.c，随 keel-abi 静态库一起链接）。
 *
 * 只暴露内嵌 bundle 解包需要的最小面；Kotlin 侧由 keelabi 的 cinterop 生成到
 * `moe.lolosia.keel.abi.c`。压缩只在构建期做（Gradle 插件用 zstd-jni），
 * 运行期不参与压缩。
 */

/* 帧头里记录的解压后大小；未知 / 非法时返回 ZSTD_CONTENTSIZE_UNKNOWN / ERROR。 */
unsigned long long ZSTD_getFrameContentSize(const void *src, size_t srcSize);

/* 一次性解压；返回值是写入字节数或错误码（用 ZSTD_isError 判断）。 */
size_t ZSTD_decompress(void *dst, size_t dstCapacity, const void *src, size_t srcSize);

/* 返回值是否为错误码。 */
unsigned ZSTD_isError(size_t code);

/* 错误码的简短描述；单文件解码器已剥掉详细文案，只用于日志兜底。 */
const char *ZSTD_getErrorName(size_t code);

#ifdef __cplusplus
}
#endif

#endif
