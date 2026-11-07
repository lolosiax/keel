/*
 * Host 退出钩子：Ctrl+C / SIGINT / SIGTERM 只置一个原子标志。
 *
 * 不在信号/控制台处理函数里触碰 Kotlin：Host 主循环轮询 keel_shutdown_requested()，
 * 看到置位后走正常的 HTTP 停止 → 插件 SHUTDOWN → 收尾流程。
 */

#include "keel_abi.h"
#include "keel_platform.h"

#ifdef _WIN32

static volatile LONG g_shutdown = 0;

static BOOL WINAPI keel_console_ctrl(DWORD type) {
    switch (type) {
        case CTRL_C_EVENT:
        case CTRL_BREAK_EVENT:
        case CTRL_CLOSE_EVENT:
        case CTRL_SHUTDOWN_EVENT:
            InterlockedExchange(&g_shutdown, 1);
            return TRUE;
        default:
            return FALSE;
    }
}

void keel_install_shutdown_hook(void) {
    SetConsoleCtrlHandler(keel_console_ctrl, TRUE);
}

int keel_shutdown_requested(void) {
    return InterlockedCompareExchange(&g_shutdown, 0, 0) != 0;
}

#else

#include <signal.h>

static volatile sig_atomic_t g_shutdown = 0;

static void keel_signal_handler(int sig) {
    (void)sig;
    g_shutdown = 1;
}

void keel_install_shutdown_hook(void) {
    signal(SIGINT, keel_signal_handler);
    signal(SIGTERM, keel_signal_handler);
}

int keel_shutdown_requested(void) {
    return g_shutdown != 0;
}

#endif
