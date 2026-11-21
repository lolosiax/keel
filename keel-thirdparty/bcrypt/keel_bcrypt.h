/*
 * crypt_blowfish 的 keel 适配层。
 *
 * 上游 `_crypt_*` 接口用 `unsigned long` 传 cost，在 mingw（32 位）与 linux（64 位）
 * 上是不同的 C 类型，cinterop 会生成 UInt / ULong 两种签名；这里统一收成 `int`，
 * Kotlin 侧只需一份声明。
 *
 * 两个函数都是重入的（无全局状态），可在插件 Worker 线程直接调用。
 *
 * output 容量要求：keel_bcrypt_hash 至少 61 字节，keel_bcrypt_gensalt 至少 30 字节。
 * 成功返回 0，参数不合法返回 -1。
 */

#ifndef KEEL_BCRYPT_H
#define KEEL_BCRYPT_H

int keel_bcrypt_hash(const char *password, const char *setting,
	char *output, int output_size);

int keel_bcrypt_gensalt(int rounds, const char *input, int input_size,
	char *output, int output_size);

#endif
