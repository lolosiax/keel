/*
 * 控制台输入。
 *
 * 读 stdin 的线程完全在 C 侧：只把整行写进环形缓冲，不调用任何 Kotlin，
 * 因此不违反「一个线程只能进入一个运行时」。Host 主循环用 keel_console_poll
 * 非阻塞取行，命令分发与事件泵仍在同一个线程上串行执行。
 *
 * Windows 终端用 ReadConsoleW 读宽字符再转 UTF-8（fgets 会按 ANSI 代码页解释，
 * 中文会乱码）；stdin 被重定向时退回 fgets，按 UTF-8 字节处理。
 */

#include "keel_abi.h"
#include "keel_platform.h"

#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

#define KEEL_CONSOLE_SLOTS 16u
#define KEEL_CONSOLE_LINE 1024u

static char g_lines[KEEL_CONSOLE_SLOTS][KEEL_CONSOLE_LINE];
static uint32_t g_lengths[KEEL_CONSOLE_SLOTS];
static uint32_t g_head;
static uint32_t g_count;

static keel_mutex_t g_mutex;
static keel_thread_t g_thread;
static int g_mutex_ready;
static int g_started;

static void console_push(const char *text, size_t length) {
    if (length == 0) return;
    if (length >= KEEL_CONSOLE_LINE) length = KEEL_CONSOLE_LINE - 1;

    keel_mutex_lock(&g_mutex);
    if (g_count < KEEL_CONSOLE_SLOTS) {
        uint32_t index = (g_head + g_count) % KEEL_CONSOLE_SLOTS;
        memcpy(g_lines[index], text, length);
        g_lines[index][length] = '\0';
        g_lengths[index] = (uint32_t)length;
        g_count++;
    }
    keel_mutex_unlock(&g_mutex);
}

static void trim_eol(char *text, size_t *length) {
    while (*length > 0) {
        char c = text[*length - 1];
        if (c != '\n' && c != '\r') break;
        text[--(*length)] = '\0';
    }
}

#ifdef _WIN32

/* @return 0 表示读到 EOF / 输入不可用 */
static int console_read(char *out, size_t capacity, size_t *length) {
    HANDLE handle = GetStdHandle(STD_INPUT_HANDLE);
    DWORD mode = 0;

    if (handle != INVALID_HANDLE_VALUE && handle != NULL && GetConsoleMode(handle, &mode)) {
        wchar_t wide[KEEL_CONSOLE_LINE];
        DWORD read = 0;
        if (!ReadConsoleW(handle, wide, (DWORD)(KEEL_CONSOLE_LINE - 1), &read, NULL)) return 0;
        if (read == 0) return 0;

        int converted = WideCharToMultiByte(
            CP_UTF8, 0, wide, (int)read, out, (int)capacity - 1, NULL, NULL);
        if (converted <= 0) return 0;
        *length = (size_t)converted;
        out[*length] = '\0';
        trim_eol(out, length);
        return 1;
    }

    if (!fgets(out, (int)capacity, stdin)) return 0;
    *length = strlen(out);
    trim_eol(out, length);
    return 1;
}

#else

static int console_read(char *out, size_t capacity, size_t *length) {
    if (!fgets(out, (int)capacity, stdin)) return 0;
    *length = strlen(out);
    trim_eol(out, length);
    return 1;
}

#endif

static KEEL_THREAD_RETURN console_main(KEEL_THREAD_ARG arg) {
    (void)arg;
    char line[KEEL_CONSOLE_LINE];

    for (;;) {
        size_t length = 0;
        if (!console_read(line, sizeof(line), &length)) break;
        console_push(line, length);
    }
    return KEEL_THREAD_RETURN_VALUE;
}

/* stdin 是否是终端：管道 / 重定向时为 0，Host 不打印提示符。 */
static int console_interactive(void) {
#ifdef _WIN32
    HANDLE handle = GetStdHandle(STD_INPUT_HANDLE);
    DWORD mode = 0;
    return (handle != INVALID_HANDLE_VALUE && handle != NULL && GetConsoleMode(handle, &mode)) ? 1 : 0;
#else
    return isatty(STDIN_FILENO) ? 1 : 0;
#endif
}

int keel_console_start(void) {
    if (g_started) return console_interactive();

    if (!g_mutex_ready) {
        keel_mutex_init(&g_mutex);
        g_mutex_ready = 1;
    }
    if (keel_thread_start(&g_thread, console_main, NULL) != 0) return 0;
    g_started = 1;
    return console_interactive();
}

int keel_console_poll(char *buf, uint32_t capacity) {
    if (!buf || capacity == 0 || !g_mutex_ready) return 0;

    int result = 0;
    keel_mutex_lock(&g_mutex);
    if (g_count > 0) {
        uint32_t length = g_lengths[g_head];
        if (length >= capacity) length = capacity - 1;
        memcpy(buf, g_lines[g_head], length);
        buf[length] = '\0';
        g_head = (g_head + 1) % KEEL_CONSOLE_SLOTS;
        g_count--;
        result = (int)length;
    }
    keel_mutex_unlock(&g_mutex);
    return result;
}
