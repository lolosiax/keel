/*
 * 文件日志与崩溃现场。
 *
 * 两块内容：
 * 1) KeelLogFile：按日切割的文本日志（`<dir>/keel-YYYY-MM-DD.log`），线程安全；
 * 2) 崩溃归因：Host 把最小现场写进 beacon 文件并挂住自己，同一可执行文件的
 *    `--watchdog` 进程发现后做内存转储、写报告、再终止 Host。
 *
 * 信号 / 异常处理函数里只做「读静态模块表 + 填固定结构 + 一次 write + 睡住」，
 * 不 malloc、不 printf、不调用任何 Kotlin；重活全部交给健康的看门狗进程。
 */

#define _GNU_SOURCE 1

/*
 * mingw 默认走 MSVCRT 的 printf，不认 %llu / %016llx；启用 mingw 自己的
 * C99 实现，报告里的 64 位地址才能正确打印。
 */
#if defined(_WIN32) && !defined(__USE_MINGW_ANSI_STDIO)
#define __USE_MINGW_ANSI_STDIO 1
#endif

#include "keel_abi.h"
#include "keel_platform.h"

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#include <windows.h>
#include <tlhelp32.h>
#include <direct.h>
#include <fcntl.h>
#include <io.h>
#include <process.h>
#include <sys/stat.h>
#else
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>
#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>

extern char **environ;
#endif

#define KEEL_PATH_MAX 1024
#define KEEL_MAX_MODULES 32
#define KEEL_MODULE_NAME 64
#define KEEL_CRASH_MAGIC 0x3143524Bu /* 'KRC1'，小端 */
#define KEEL_CRASH_VERSION 1u
#define KEEL_LOG_TAIL 8192u

typedef struct KeelCrashRecord {
    uint32_t magic;
    uint32_t version;
    uint32_t pid;
    uint32_t tid;
    uint32_t signal;   /* POSIX 信号号 / Windows 异常码 */
    uint32_t reserved;
    uint64_t timestamp_ms;
    uint64_t fault_address;
    uint64_t pc;
    uint64_t sp;
    uint64_t bp;
    uint64_t module_base;
    char module[KEEL_MODULE_NAME];
    char build[KEEL_MODULE_NAME];
} KeelCrashRecord;

typedef struct KeelModuleEntry {
    uint64_t base;
    uint64_t size; /* 0 表示未知，只按「最近的下界」归因 */
    char name[KEEL_MODULE_NAME];
} KeelModuleEntry;

static KeelModuleEntry g_modules[KEEL_MAX_MODULES];
static volatile int32_t g_module_count = 0;
static int g_crash_fd = -1;
static char g_build_id[KEEL_MODULE_NAME];

/* ------------------------------------------------------------------ */
/* 通用小工具                                                          */
/* ------------------------------------------------------------------ */

static void keel_copy(char *dst, size_t cap, const char *src) {
    size_t i = 0;
    if (!cap) return;
    while (src && src[i] && i + 1 < cap) {
        dst[i] = src[i];
        i++;
    }
    dst[i] = '\0';
}

static const char *keel_basename(const char *path) {
    const char *slash = path;
    if (!path) return "";
    for (const char *p = path; *p; p++) {
        if (*p == '/' || *p == '\\') slash = p + 1;
    }
    return slash;
}

static void keel_sleep_ms(uint32_t ms) {
#ifdef _WIN32
    Sleep(ms);
#else
    struct timespec ts;
    ts.tv_sec = (time_t)(ms / 1000u);
    ts.tv_nsec = (long)(ms % 1000u) * 1000000L;
    nanosleep(&ts, NULL);
#endif
}

static uint64_t keel_epoch_ms(void) {
#ifdef _WIN32
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | (uint64_t)ft.dwLowDateTime;
    return t / 10000ull - 11644473600000ull;
#else
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (uint64_t)ts.tv_sec * 1000ull + (uint64_t)(ts.tv_nsec / 1000000);
#endif
}

/* 本地日期 / 时间；split=1 时只填日期。 */
static void keel_local_parts(int *year, int *month, int *day, int *hour, int *minute, int *second,
                             int *millis) {
#ifdef _WIN32
    SYSTEMTIME st;
    GetLocalTime(&st);
    *year = st.wYear;
    *month = st.wMonth;
    *day = st.wDay;
    *hour = st.wHour;
    *minute = st.wMinute;
    *second = st.wSecond;
    *millis = st.wMilliseconds;
#else
    struct timespec ts;
    struct tm tmv;
    clock_gettime(CLOCK_REALTIME, &ts);
    localtime_r(&ts.tv_sec, &tmv);
    *year = tmv.tm_year + 1900;
    *month = tmv.tm_mon + 1;
    *day = tmv.tm_mday;
    *hour = tmv.tm_hour;
    *minute = tmv.tm_min;
    *second = tmv.tm_sec;
    *millis = (int)(ts.tv_nsec / 1000000);
#endif
}

static void keel_day_string(char *out, size_t cap) {
    int y, mo, d, h, mi, s, ms;
    keel_local_parts(&y, &mo, &d, &h, &mi, &s, &ms);
    snprintf(out, cap, "%04d-%02d-%02d", y, mo, d);
}

static void keel_datetime_string(char *out, size_t cap) {
    int y, mo, d, h, mi, s, ms;
    keel_local_parts(&y, &mo, &d, &h, &mi, &s, &ms);
    snprintf(out, cap, "%04d-%02d-%02d %02d:%02d:%02d.%03d", y, mo, d, h, mi, s, ms);
}

/* ------------------------------------------------------------------ */
/* 路径 / 文件（Windows 走 UTF-8 → UTF-16，避免中文目录失败）           */
/* ------------------------------------------------------------------ */

#ifdef _WIN32
static void keel_mkdir_one(const char *path) {
    wchar_t w[KEEL_PATH_MAX];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, KEEL_PATH_MAX)) _wmkdir(w);
}

static FILE *keel_open_utf8(const char *path, const char *mode) {
    wchar_t wp[KEEL_PATH_MAX];
    wchar_t wm[8];
    if (!MultiByteToWideChar(CP_UTF8, 0, path, -1, wp, KEEL_PATH_MAX)) return NULL;
    if (!MultiByteToWideChar(CP_UTF8, 0, mode, -1, wm, 8)) return NULL;
    return _wfopen(wp, wm);
}

static int keel_cwd(char *out, size_t cap) {
    wchar_t w[KEEL_PATH_MAX];
    if (!_wgetcwd(w, KEEL_PATH_MAX)) return -1;
    return WideCharToMultiByte(CP_UTF8, 0, w, -1, out, (int)cap, NULL, NULL) ? 0 : -1;
}

static void keel_wide_to_utf8(const wchar_t *source, char *dest, size_t capacity) {
    if (!dest || !capacity) return;
    dest[0] = '\0';
    if (!source) return;
    WideCharToMultiByte(CP_UTF8, 0, source, -1, dest, (int)capacity, NULL, NULL);
}

static int keel_open_crash_fd(const char *path) {
    wchar_t wp[KEEL_PATH_MAX];
    if (!MultiByteToWideChar(CP_UTF8, 0, path, -1, wp, KEEL_PATH_MAX)) return -1;
    return _wopen(wp, _O_CREAT | _O_WRONLY | _O_TRUNC | _O_BINARY, _S_IREAD | _S_IWRITE);
}
#else
static void keel_mkdir_one(const char *path) {
    mkdir(path, 0755);
}

static FILE *keel_open_utf8(const char *path, const char *mode) {
    return fopen(path, mode);
}

static int keel_cwd(char *out, size_t cap) {
    return getcwd(out, cap) ? 0 : -1;
}

static int keel_open_crash_fd(const char *path) {
    return open(path, O_CREAT | O_WRONLY | O_TRUNC, 0644);
}
#endif

static void keel_remove_utf8(const char *path) {
    if (!path || !*path) return;
#ifdef _WIN32
    {
        wchar_t w[KEEL_PATH_MAX];
        if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, KEEL_PATH_MAX)) _wremove(w);
    }
#else
    remove(path);
#endif
}

static void keel_mkdirs(const char *path) {
    char buf[KEEL_PATH_MAX];
    size_t len;
    if (!path || !*path) return;
    keel_copy(buf, sizeof buf, path);
    len = strlen(buf);
    for (size_t i = 1; i < len; i++) {
        if (buf[i] == '/' || buf[i] == '\\') {
            char saved = buf[i];
            buf[i] = '\0';
            keel_mkdir_one(buf);
            buf[i] = saved;
        }
    }
    keel_mkdir_one(buf);
}

/* ------------------------------------------------------------------ */
/* 按日切割的文件日志                                                  */
/* ------------------------------------------------------------------ */

struct KeelLogFileTag {
    keel_mutex_t lock;
    FILE *file;
    char dir[KEEL_PATH_MAX];
    char day[16];
};

static void keel_logfile_rotate(KeelLogFile *lf, int force) {
    char day[16];
    char path[KEEL_PATH_MAX];
    keel_day_string(day, sizeof day);
    if (!force && lf->file && strcmp(day, lf->day) == 0) return;

    if (lf->file) {
        fclose(lf->file);
        lf->file = NULL;
    }
    snprintf(path, sizeof path, "%s/keel-%s.log", lf->dir, day);
    lf->file = keel_open_utf8(path, "ab");
    keel_copy(lf->day, sizeof lf->day, day);
}

KeelLogFile *keel_logfile_open(const char *dir) {
    KeelLogFile *lf;
    if (!dir || !*dir) return NULL;
    lf = (KeelLogFile *)calloc(1, sizeof *lf);
    if (!lf) return NULL;
    keel_mutex_init(&lf->lock);
    keel_copy(lf->dir, sizeof lf->dir, dir);
    keel_mkdirs(dir);
    keel_logfile_rotate(lf, 1);
    return lf;
}

void keel_logfile_write(KeelLogFile *file, const char *data, uint32_t size) {
    if (!file || !data || !size) return;
    keel_mutex_lock(&file->lock);
    keel_logfile_rotate(file, 0);
    if (file->file) {
        fwrite(data, 1, size, file->file);
        /* 逐行刷盘：崩溃后日志末尾才不会缺最后几行 */
        fflush(file->file);
    }
    keel_mutex_unlock(&file->lock);
}

void keel_logfile_close(KeelLogFile *file) {
    if (!file) return;
    keel_mutex_lock(&file->lock);
    if (file->file) {
        fclose(file->file);
        file->file = NULL;
    }
    keel_mutex_unlock(&file->lock);
    keel_mutex_destroy(&file->lock);
    free(file);
}

/* ------------------------------------------------------------------ */
/* 模块表（崩溃归因用的只读快照）                                      */
/* ------------------------------------------------------------------ */

static void keel_add_module(const char *name, uint64_t base, uint64_t size) {
    int32_t index = g_module_count;
    KeelModuleEntry *entry;
    if (index < 0 || index >= KEEL_MAX_MODULES || !base) return;
    entry = &g_modules[index];
    entry->base = base;
    entry->size = size;
    keel_copy(entry->name, sizeof entry->name, name);
    g_module_count = index + 1;
}

static const KeelModuleEntry *keel_find_module(uint64_t address) {
    const KeelModuleEntry *best = NULL;
    int32_t count = g_module_count;
    if (count > KEEL_MAX_MODULES) count = KEEL_MAX_MODULES;
    for (int32_t i = 0; i < count; i++) {
        const KeelModuleEntry *entry = &g_modules[i];
        if (address < entry->base) continue;
        if (entry->size && address >= entry->base + entry->size) continue;
        if (!best || entry->base > best->base) best = entry;
    }
    return best;
}

#ifdef _WIN32
/* PE OptionalHeader.SizeOfImage：崩溃归因需要模块的地址范围。 */
static uint64_t keel_pe_image_size(const unsigned char *base) {
    uint32_t lfanew;
    const unsigned char *nt;
    if (!base || base[0] != 'M' || base[1] != 'Z') return 0;
    memcpy(&lfanew, base + 0x3C, sizeof lfanew);
    nt = base + lfanew;
    if (memcmp(nt, "PE\0\0", 4) != 0) return 0;
    {
        uint32_t size;
        memcpy(&size, nt + 24 + 56, sizeof size);
        return size;
    }
}

static void keel_register_module_impl(const char *name, const char *path) {
    wchar_t wname[KEEL_PATH_MAX];
    HMODULE module;
    uint64_t base;
    if (!MultiByteToWideChar(CP_UTF8, 0, keel_basename(path), -1, wname, KEEL_PATH_MAX)) return;
    module = GetModuleHandleW(wname);
    if (!module) return;
    base = (uint64_t)(uintptr_t)module;
    keel_add_module(name, base, keel_pe_image_size((const unsigned char *)(uintptr_t)base));
}
#else
/* 从 /proc/self/maps 求某个模块（按文件名匹配）的地址范围。 */
static void keel_register_module_impl(const char *name, const char *path) {
    FILE *maps;
    char line[1024];
    const char *want = keel_basename(path);
    uint64_t lo = 0;
    uint64_t hi = 0;
    if (!*want) return;
    maps = fopen("/proc/self/maps", "rb");
    if (!maps) return;

    while (fgets(line, sizeof line, maps)) {
        char *p = line;
        char *end_ptr = NULL;
        unsigned long long start;
        unsigned long long end;
        start = strtoull(p, &end_ptr, 16);
        if (!end_ptr || *end_ptr != '-') continue;
        end = strtoull(end_ptr + 1, &p, 16);
        if (!start || end <= start) continue;
        /* 跳过 perms / offset / dev / inode 四个字段 */
        for (int field = 0; field < 4; field++) {
            while (*p == ' ') p++;
            while (*p && *p != ' ') p++;
        }
        while (*p == ' ') p++;
        if (!*p || *p == '\n') continue;
        if (!strstr(keel_basename(p), want)) continue;
        if (!lo || start < lo) lo = start;
        if (end > hi) hi = end;
    }
    fclose(maps);
    if (lo && hi > lo) keel_add_module(name, lo, hi - lo);
}
#endif

void keel_crash_register_module(const char *name, const char *library_path) {
    if (!name || !library_path || !*library_path) return;
    keel_register_module_impl(name, library_path);
}

/* ------------------------------------------------------------------ */
/* 崩溃现场：写 beacon 并挂住自己                                       */
/* ------------------------------------------------------------------ */

static void keel_crash_fill(KeelCrashRecord *rec, uint32_t signal, uint64_t fault, uint64_t pc,
                            uint64_t sp, uint64_t bp) {
    const KeelModuleEntry *module;
    memset(rec, 0, sizeof *rec);
    rec->magic = KEEL_CRASH_MAGIC;
    rec->version = KEEL_CRASH_VERSION;
    rec->signal = signal;
    rec->timestamp_ms = keel_epoch_ms();
    rec->fault_address = fault;
    rec->pc = pc;
    rec->sp = sp;
    rec->bp = bp;
    keel_copy(rec->build, sizeof rec->build, g_build_id);
#ifdef _WIN32
    rec->pid = (uint32_t)GetCurrentProcessId();
    rec->tid = (uint32_t)GetCurrentThreadId();
#else
    rec->pid = (uint32_t)getpid();
    rec->tid = (uint32_t)syscall(SYS_gettid);
#endif
    module = keel_find_module(pc ? pc : fault);
    if (module) {
        rec->module_base = module->base;
        keel_copy(rec->module, sizeof rec->module, module->name);
    }
}

static void keel_crash_write(const KeelCrashRecord *rec) {
    if (g_crash_fd < 0) return;
#ifdef _WIN32
    _write(g_crash_fd, rec, (unsigned)sizeof *rec);
#else
    {
        ssize_t written = write(g_crash_fd, rec, sizeof *rec);
        (void)written;
    }
#endif
}

/* 写完后挂住，等看门狗把内存读走；最多等 10 秒再自行退出。 */
static void keel_crash_wait(void) {
    for (int i = 0; i < 100; i++) keel_sleep_ms(100);
#ifdef _WIN32
    TerminateProcess(GetCurrentProcess(), 3);
#else
    _exit(1);
#endif
}

#ifdef _WIN32
static int keel_fatal_exception(DWORD code) {
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:
        case EXCEPTION_IN_PAGE_ERROR:
        case EXCEPTION_ILLEGAL_INSTRUCTION:
        case EXCEPTION_PRIV_INSTRUCTION:
        case EXCEPTION_INT_DIVIDE_BY_ZERO:
        case EXCEPTION_INT_OVERFLOW:
        case EXCEPTION_STACK_OVERFLOW:
        case EXCEPTION_ARRAY_BOUNDS_EXCEEDED:
        case EXCEPTION_DATATYPE_MISALIGNMENT:
        case 0xC0000409u: /* STATUS_STACK_BUFFER_OVERRUN */
            return 1;
        default:
            return 0;
    }
}

static LONG WINAPI keel_crash_veh(EXCEPTION_POINTERS *info) {
    KeelCrashRecord rec;
    DWORD code;
    uint64_t fault;
    uint64_t pc = 0, sp = 0, bp = 0;
    if (!info || !info->ExceptionRecord) return EXCEPTION_CONTINUE_SEARCH;
    code = info->ExceptionRecord->ExceptionCode;
    if (!keel_fatal_exception(code)) return EXCEPTION_CONTINUE_SEARCH;

    fault = (uint64_t)(uintptr_t)info->ExceptionRecord->ExceptionAddress;
    if (code == EXCEPTION_ACCESS_VIOLATION && info->ExceptionRecord->NumberParameters >= 2) {
        fault = (uint64_t)info->ExceptionRecord->ExceptionInformation[1];
    }
    if (info->ContextRecord) {
        pc = (uint64_t)info->ContextRecord->Rip;
        sp = (uint64_t)info->ContextRecord->Rsp;
        bp = (uint64_t)info->ContextRecord->Rbp;
    }
    keel_crash_fill(&rec, (uint32_t)code, fault, pc, sp, bp);
    keel_crash_write(&rec);
    keel_crash_wait();
    return EXCEPTION_CONTINUE_SEARCH;
}
#else
static void keel_crash_signal(int sig, siginfo_t *info, void *context) {
    KeelCrashRecord rec;
    uint64_t fault = (uint64_t)(uintptr_t)(info ? info->si_addr : NULL);
    uint64_t pc = 0, sp = 0, bp = 0;
    ucontext_t *uc = (ucontext_t *)context;
    if (uc) {
#if defined(__x86_64__)
        pc = (uint64_t)uc->uc_mcontext.gregs[REG_RIP];
        sp = (uint64_t)uc->uc_mcontext.gregs[REG_RSP];
        bp = (uint64_t)uc->uc_mcontext.gregs[REG_RBP];
#elif defined(__aarch64__)
        pc = (uint64_t)uc->uc_mcontext.pc;
        sp = (uint64_t)uc->uc_mcontext.sp;
        bp = (uint64_t)uc->uc_mcontext.regs[29];
#endif
    }
    keel_crash_fill(&rec, (uint32_t)sig, fault, pc, sp, bp);
    keel_crash_write(&rec);
    keel_crash_wait();
}
#endif

void keel_crash_install(const char *log_dir, const char *build_id) {
    char dir[KEEL_PATH_MAX];
    char path[KEEL_PATH_MAX];
    if (!log_dir || !*log_dir) return;

    keel_copy(g_build_id, sizeof g_build_id, build_id ? build_id : "");

    snprintf(dir, sizeof dir, "%s/crash", log_dir);
    keel_mkdirs(dir);
#ifdef _WIN32
    snprintf(path, sizeof path, "%s/watchdog-%lu.bin", dir, (unsigned long)GetCurrentProcessId());
#else
    snprintf(path, sizeof path, "%s/watchdog-%ld.bin", dir, (long)getpid());
#endif
    if (g_crash_fd < 0) g_crash_fd = keel_open_crash_fd(path);

    /* Host 自己也要在归因表里，否则 Host 侧崩溃会被当成「未知模块」 */
    {
#ifdef _WIN32
        wchar_t wpath[KEEL_PATH_MAX];
        char utf8[KEEL_PATH_MAX];
        if (GetModuleFileNameW(NULL, wpath, KEEL_PATH_MAX)) {
            if (WideCharToMultiByte(CP_UTF8, 0, wpath, -1, utf8, KEEL_PATH_MAX, NULL, NULL)) {
                keel_register_module_impl("keel-host", utf8);
            }
        }
#else
        char exe[KEEL_PATH_MAX];
        ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
        if (n > 0) {
            exe[n] = '\0';
            keel_register_module_impl("keel-host", exe);
        }
#endif
    }

#ifdef _WIN32
    AddVectoredExceptionHandler(1, keel_crash_veh);
#else
    {
        /* 栈溢出时处理器必须有独立的栈，否则二次崩溃 */
        stack_t stack;
        struct sigaction action;
        memset(&stack, 0, sizeof stack);
        stack.ss_size = 256 * 1024;
        stack.ss_sp = malloc(stack.ss_size);
        stack.ss_flags = 0;
        if (stack.ss_sp) sigaltstack(&stack, NULL);

        memset(&action, 0, sizeof action);
        action.sa_sigaction = keel_crash_signal;
        action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESETHAND;
        sigemptyset(&action.sa_mask);
        sigaction(SIGSEGV, &action, NULL);
        sigaction(SIGBUS, &action, NULL);
        sigaction(SIGILL, &action, NULL);
        sigaction(SIGFPE, &action, NULL);
        sigaction(SIGABRT, &action, NULL);
    }
#endif
}

/* ------------------------------------------------------------------ */
/* 看门狗                                                              */
/* ------------------------------------------------------------------ */

uint64_t keel_watchdog_spawn(void) {
#ifdef _WIN32
    wchar_t exe[KEEL_PATH_MAX];
    wchar_t command[KEEL_PATH_MAX + 64];
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    if (!GetModuleFileNameW(NULL, exe, KEEL_PATH_MAX)) return 0;
    _snwprintf(command, KEEL_PATH_MAX + 64, L"\"%s\" --watchdog --parent-pid=%lu", exe,
               (unsigned long)GetCurrentProcessId());

    memset(&startup, 0, sizeof startup);
    startup.cb = sizeof startup;
    memset(&process, 0, sizeof process);
    if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, 0, NULL, NULL, &startup, &process)) {
        return 0;
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return (uint64_t)process.dwProcessId;
#else
    char exe[KEEL_PATH_MAX];
    char pid_arg[64];
    char *argv[4];
    ssize_t n;
    pid_t pid;

    n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (n <= 0) return 0;
    exe[n] = '\0';

    snprintf(pid_arg, sizeof pid_arg, "--parent-pid=%ld", (long)getpid());
    argv[0] = exe;
    argv[1] = (char *)"--watchdog";
    argv[2] = pid_arg;
    argv[3] = NULL;

    if (posix_spawn(&pid, exe, NULL, NULL, argv, environ) != 0) return 0;

    /*
     * Yama ptrace_scope=1 下子进程默认不能 attach 父进程，这里显式放行，
     * 看门狗才能读 /proc/<pid>/mem 做转储。
     */
    prctl(PR_SET_PTRACER, pid, 0, 0, 0);

    /* 看门狗退出后立即回收，不留下僵尸进程 */
    signal(SIGCHLD, SIG_IGN);
    return (uint64_t)pid;
#endif
}

static int keel_parent_alive(uint64_t pid) {
#ifdef _WIN32
    HANDLE handle = OpenProcess(SYNCHRONIZE, FALSE, (DWORD)pid);
    DWORD state;
    if (!handle) return 0;
    state = WaitForSingleObject(handle, 0);
    CloseHandle(handle);
    return state != WAIT_OBJECT_0;
#else
    char path[64];
    char buf[512];
    FILE *stat;
    size_t n;
    char *close_paren;
    if (kill((pid_t)pid, 0) != 0 && errno == ESRCH) return 0;
    snprintf(path, sizeof path, "/proc/%llu/stat", (unsigned long long)pid);
    stat = fopen(path, "rb");
    if (!stat) return 0;
    n = fread(buf, 1, sizeof buf - 1, stat);
    fclose(stat);
    buf[n] = '\0';
    close_paren = strrchr(buf, ')');
    if (close_paren && close_paren[1] == ' ') {
        char state = close_paren[2];
        if (state == 'Z' || state == 'X') return 0;
    }
    return 1;
#endif
}

static int keel_read_crash(const char *path, KeelCrashRecord *rec) {
    FILE *file = keel_open_utf8(path, "rb");
    size_t got;
    if (!file) return 0;
    memset(rec, 0, sizeof *rec);
    got = fread(rec, 1, sizeof *rec, file);
    fclose(file);
    if (got < offsetof(KeelCrashRecord, module)) return 0;
    return rec->magic == KEEL_CRASH_MAGIC ? 1 : 0;
}

static void keel_signal_name(uint32_t code, char *out, size_t cap) {
#ifdef _WIN32
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:
            snprintf(out, cap, "ACCESS_VIOLATION (0x%08X)", code);
            break;
        case EXCEPTION_STACK_OVERFLOW:
            snprintf(out, cap, "STACK_OVERFLOW (0x%08X)", code);
            break;
        case EXCEPTION_ILLEGAL_INSTRUCTION:
            snprintf(out, cap, "ILLEGAL_INSTRUCTION (0x%08X)", code);
            break;
        case EXCEPTION_INT_DIVIDE_BY_ZERO:
            snprintf(out, cap, "INT_DIVIDE_BY_ZERO (0x%08X)", code);
            break;
        default:
            snprintf(out, cap, "EXCEPTION 0x%08X", code);
            break;
    }
#else
    const char *name = "SIGNAL";
    switch (code) {
        case SIGSEGV:
            name = "SIGSEGV";
            break;
        case SIGBUS:
            name = "SIGBUS";
            break;
        case SIGILL:
            name = "SIGILL";
            break;
        case SIGFPE:
            name = "SIGFPE";
            break;
        case SIGABRT:
            name = "SIGABRT";
            break;
        default:
            break;
    }
    snprintf(out, cap, "%s (%u)", name, code);
#endif
}

static void keel_hexdump(FILE *out, const unsigned char *data, size_t size, uint64_t base) {
    for (size_t offset = 0; offset < size; offset += 16) {
        size_t count = size - offset < 16 ? size - offset : 16;
        fprintf(out, "%016llx  ", (unsigned long long)(base + offset));
        for (size_t i = 0; i < 16; i++) {
            if (i < count) {
                fprintf(out, "%02x ", data[offset + i]);
            } else {
                fputs("   ", out);
            }
        }
        fputc(' ', out);
        for (size_t i = 0; i < count; i++) {
            unsigned char c = data[offset + i];
            fputc(c >= 0x20 && c < 0x7f ? c : '.', out);
        }
        fputc('\n', out);
    }
}

static void keel_dump_log_tail(FILE *out, const char *log_dir) {
    char day[16];
    char path[KEEL_PATH_MAX];
    FILE *file;
    long size;
    long take;
    unsigned char *buffer;
    size_t got;

    keel_day_string(day, sizeof day);
    snprintf(path, sizeof path, "%s/keel-%s.log", log_dir, day);
    file = keel_open_utf8(path, "rb");
    if (!file) {
        fputs("(没有找到当日日志文件)\n", out);
        return;
    }
    fseek(file, 0, SEEK_END);
    size = ftell(file);
    take = size > (long)KEEL_LOG_TAIL ? (long)KEEL_LOG_TAIL : size;
    if (take <= 0) {
        fclose(file);
        fputs("(日志为空)\n", out);
        return;
    }
    buffer = (unsigned char *)malloc((size_t)take + 1);
    if (!buffer) {
        fclose(file);
        fputs("(日志读取失败：内存不足)\n", out);
        return;
    }
    fseek(file, size - take, SEEK_SET);
    got = fread(buffer, 1, (size_t)take, file);
    fclose(file);
    buffer[got] = '\0';
    /* 从行首开始，避免首行被截断 */
    if (take == (long)KEEL_LOG_TAIL) {
        size_t skip = 0;
        while (skip < got && buffer[skip] != '\n') skip++;
        if (skip < got) skip++;
        fwrite(buffer + skip, 1, got - skip, out);
    } else {
        fwrite(buffer, 1, got, out);
    }
    free(buffer);
}

/* ------------------------------------------------------------------ */
/* 线程栈转储                                                          */
/* ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ */
/* 符号化：从模块文件自身的符号表还原 kfun: 名称                        */
/* ------------------------------------------------------------------ */

typedef struct KeelSymbol {
    uint64_t rva;
    char *name;
} KeelSymbol;

typedef struct KeelSymbolTable {
    KeelSymbol *items;
    int count;
    int capacity;
    int loaded;
} KeelSymbolTable;

static char *keel_strdup(const char *text) {
    size_t length = strlen(text) + 1;
    char *copy = (char *)malloc(length);
    if (copy) memcpy(copy, text, length);
    return copy;
}

static void keel_symbols_free(KeelSymbolTable *table) {
    if (!table) return;
    for (int i = 0; i < table->count; i++) free(table->items[i].name);
    free(table->items);
    table->items = NULL;
    table->count = 0;
    table->capacity = 0;
    table->loaded = 0;
}

static void keel_symbols_add(KeelSymbolTable *table, uint64_t rva, const char *name) {
    KeelSymbol *items;
    char *copy;
    if (!name || !*name) return;
    if (table->count >= table->capacity) {
        int next = table->capacity ? table->capacity * 2 : 1024;
        items = (KeelSymbol *)realloc(table->items, sizeof(KeelSymbol) * (size_t)next);
        if (!items) return;
        table->items = items;
        table->capacity = next;
    }
    copy = keel_strdup(name);
    if (!copy) return;
    table->items[table->count].rva = rva;
    table->items[table->count].name = copy;
    table->count++;
}

static int keel_symbol_compare(const void *left, const void *right) {
    const KeelSymbol *a = (const KeelSymbol *)left;
    const KeelSymbol *b = (const KeelSymbol *)right;
    if (a->rva < b->rva) return -1;
    if (a->rva > b->rva) return 1;
    return 0;
}

static unsigned char *keel_read_file(const char *path, size_t *out_size) {
    FILE *file;
    long size;
    unsigned char *data;
    size_t got;
    if (!path || !*path) return NULL;
    file = keel_open_utf8(path, "rb");
    if (!file) return NULL;
    fseek(file, 0, SEEK_END);
    size = ftell(file);
    if (size <= 0) {
        fclose(file);
        return NULL;
    }
    data = (unsigned char *)malloc((size_t)size);
    if (!data) {
        fclose(file);
        return NULL;
    }
    fseek(file, 0, SEEK_SET);
    got = fread(data, 1, (size_t)size, file);
    fclose(file);
    if (got != (size_t)size) {
        free(data);
        return NULL;
    }
    *out_size = got;
    return data;
}

#ifdef _WIN32
static int keel_pe_is_executable(const unsigned char *section) {
    uint32_t characteristics = 0;
    memcpy(&characteristics, section + 36, sizeof characteristics);
    return (characteristics & 0x20000000u) != 0; /* IMAGE_SCN_MEM_EXECUTE */
}

/* PE 的 COFF 符号表；mingw 默认保留 kfun: 名字。 */
static void keel_load_pe_symbols(KeelSymbolTable *table, const unsigned char *data, size_t size) {
    uint32_t pe_offset = 0;
    uint32_t symbol_table = 0;
    uint32_t symbol_count = 0;
    uint16_t section_count = 0;
    uint16_t optional_size = 0;
    const unsigned char *nt;
    const unsigned char *file_header;
    const unsigned char *sections;
    const unsigned char *records;
    const unsigned char *strings;
    size_t strings_size = 0;
    uint32_t index = 0;

    if (size < 0x40 || data[0] != 'M' || data[1] != 'Z') return;
    memcpy(&pe_offset, data + 0x3C, sizeof pe_offset);
    if ((size_t)pe_offset + 24 > size) return;
    nt = data + pe_offset;
    if (memcmp(nt, "PE\0\0", 4) != 0) return;
    file_header = nt + 4;
    memcpy(&section_count, file_header + 2, sizeof section_count);
    memcpy(&symbol_table, file_header + 8, sizeof symbol_table);
    memcpy(&symbol_count, file_header + 12, sizeof symbol_count);
    memcpy(&optional_size, file_header + 16, sizeof optional_size);
    if (!symbol_table || !symbol_count || !section_count) return;

    sections = file_header + 20 + optional_size;
    if ((size_t)(sections - data) + (size_t)section_count * 40 > size) return;
    records = data + symbol_table;
    if ((size_t)(records - data) + (size_t)symbol_count * 18 > size) return;
    strings = records + (size_t)symbol_count * 18;
    if ((size_t)(strings - data) + 4 > size) return;
    {
        uint32_t total = 0;
        memcpy(&total, strings, sizeof total);
        if (total < 4 || (size_t)(strings - data) + total > size) return;
        strings_size = total;
    }

    while (index < symbol_count) {
        const unsigned char *record = records + (size_t)index * 18;
        uint32_t zero = 0;
        uint32_t value = 0;
        int16_t section_number = 0;
        uint8_t storage_class = 0;
        uint8_t aux_count = 0;
        char inline_name[9];
        const char *name = NULL;
        const unsigned char *section;
        uint32_t section_rva = 0;
        uint64_t rva;

        memcpy(&zero, record, sizeof zero);
        memcpy(&value, record + 8, sizeof value);
        memcpy(&section_number, record + 12, sizeof section_number);
        storage_class = record[16];
        aux_count = record[17];

        index += 1u + (uint32_t)aux_count;
        if (section_number <= 0 || section_number > (int16_t)section_count) continue;
        if (storage_class != 2 /* external */ && storage_class != 3 /* static */) continue;

        if (zero != 0) {
            memcpy(inline_name, record, 8);
            inline_name[8] = '\0';
            name = inline_name;
        } else {
            uint32_t offset = 0;
            memcpy(&offset, record + 4, sizeof offset);
            if (offset < 4 || offset >= strings_size) continue;
            name = (const char *)(strings + offset);
        }

        section = sections + (size_t)(section_number - 1) * 40;
        if (!keel_pe_is_executable(section)) continue;
        memcpy(&section_rva, section + 12, sizeof section_rva); /* VirtualAddress */

        /* mingw ld 的 COFF 符号值是「段内偏移」，要加上段的 RVA 才是镜像地址 */
        if (!value) continue;
        rva = (uint64_t)section_rva + value;
        if (!rva || rva >= 0x100000000ull) continue;
        keel_symbols_add(table, rva, name);
    }
}
#else
/* ELF64 的 .symtab + 关联的 .strtab。 */
static void keel_load_elf_symbols(KeelSymbolTable *table, const unsigned char *data, size_t size) {
    uint64_t phoff = 0;
    uint64_t shoff = 0;
    uint16_t phentsize = 0;
    uint16_t phnum = 0;
    uint16_t shentsize = 0;
    uint16_t shnum = 0;
    uint64_t min_vaddr = (uint64_t)-1;
    int i;

    if (size < 64 || memcmp(data, ELFMAG, 4) != 0 || data[4] != 2) return;
    memcpy(&phoff, data + 32, sizeof phoff);
    memcpy(&shoff, data + 40, sizeof shoff);
    memcpy(&phentsize, data + 54, sizeof phentsize);
    memcpy(&phnum, data + 56, sizeof phnum);
    memcpy(&shentsize, data + 58, sizeof shentsize);
    memcpy(&shnum, data + 60, sizeof shnum);
    if (!shoff || !shnum || !shentsize) return;

    for (i = 0; i < phnum; i++) {
        const unsigned char *header = data + phoff + (size_t)i * phentsize;
        uint32_t type = 0;
        uint64_t vaddr = 0;
        if ((size_t)(header - data) + phentsize > size) break;
        memcpy(&type, header, sizeof type);
        if (type != 1) continue; /* PT_LOAD */
        memcpy(&vaddr, header + 16, sizeof vaddr);
        if (vaddr < min_vaddr) min_vaddr = vaddr;
    }
    if (min_vaddr == (uint64_t)-1) min_vaddr = 0;

    for (i = 0; i < shnum; i++) {
        const unsigned char *section = data + shoff + (size_t)i * shentsize;
        uint32_t type = 0;
        uint32_t link = 0;
        uint64_t offset = 0;
        uint64_t section_size = 0;
        uint64_t entry_size = 0;
        const unsigned char *names;
        uint64_t names_offset = 0;
        uint64_t names_size = 0;
        uint64_t index;

        if ((size_t)(section - data) + shentsize > size) break;
        memcpy(&type, section + 4, sizeof type);
        if (type != 2) continue; /* SHT_SYMTAB */
        memcpy(&offset, section + 24, sizeof offset);
        memcpy(&section_size, section + 32, sizeof section_size);
        memcpy(&link, section + 40, sizeof link);
        memcpy(&entry_size, section + 56, sizeof entry_size);
        if (!entry_size || link >= shnum) continue;

        {
            const unsigned char *strings = data + shoff + (size_t)link * shentsize;
            if ((size_t)(strings - data) + shentsize > size) continue;
            memcpy(&names_offset, strings + 24, sizeof names_offset);
            memcpy(&names_size, strings + 32, sizeof names_size);
        }
        if ((size_t)offset + (size_t)section_size > size) continue;
        if ((size_t)names_offset + (size_t)names_size > size) continue;
        names = data + names_offset;

        for (index = 0; index + entry_size <= section_size; index += entry_size) {
            const unsigned char *symbol = data + offset + (size_t)index;
            uint32_t name_offset = 0;
            uint8_t info = 0;
            uint16_t shndx = 0;
            uint64_t value = 0;
            uint8_t type;
            if (index + 24 > section_size) break;
            memcpy(&name_offset, symbol, sizeof name_offset);
            info = symbol[4];
            memcpy(&shndx, symbol + 6, sizeof shndx);
            memcpy(&value, symbol + 8, sizeof value);
            type = (uint8_t)(info & 0x0f);
            if (!value || shndx == 0 || name_offset >= names_size) continue;
            if (type != 0 && type != 2) continue; /* NOTYPE / FUNC */
            if (value < min_vaddr) continue;
            keel_symbols_add(table, value - min_vaddr, (const char *)names + name_offset);
        }
    }
}
#endif

typedef struct KeelTargetModule {
    uint64_t start;
    uint64_t end;
    char name[KEEL_MODULE_NAME];
    /* 模块文件路径：看门狗只读打开取符号表，绝不 dlopen / LoadLibrary */
    char path[KEEL_PATH_MAX];
    KeelSymbolTable symbols;
} KeelTargetModule;

/* 首次用到时解析该模块自身的符号表；失败也标记已加载，避免反复读文件。 */
static void keel_module_load_symbols(KeelTargetModule *module) {
    unsigned char *data;
    size_t size = 0;
    if (!module || module->symbols.loaded) return;
    module->symbols.loaded = 1;
    data = keel_read_file(module->path, &size);
    if (!data) return;
#ifdef _WIN32
    keel_load_pe_symbols(&module->symbols, data, size);
#else
    keel_load_elf_symbols(&module->symbols, data, size);
#endif
    free(data);
    if (module->symbols.count > 1) {
        qsort(module->symbols.items, (size_t)module->symbols.count, sizeof(KeelSymbol),
              keel_symbol_compare);
    }
}

/*
 * 地址 → 符号：在已排序的 RVA 表里找最后一个 <= rva 的符号。
 *
 * @param out_offset 收到「地址 - 符号起点」的偏移
 * @return 符号名；找不到或偏差过大时返回 NULL
 */
static const char *keel_module_symbolize(KeelTargetModule *module, uint64_t address,
                                         uint64_t *out_offset) {
    uint64_t rva;
    int low;
    int high;
    int found = -1;

    if (!module || address < module->start || address >= module->end) return NULL;
    keel_module_load_symbols(module);
    if (!module->symbols.count) return NULL;

    rva = address - module->start;
    low = 0;
    high = module->symbols.count - 1;
    while (low <= high) {
        int middle = (low + high) / 2;
        if (module->symbols.items[middle].rva <= rva) {
            found = middle;
            low = middle + 1;
        } else {
            high = middle - 1;
        }
    }
    if (found < 0) return NULL;
    {
        uint64_t delta = rva - module->symbols.items[found].rva;
        /* 超过 1MB 的偏差基本是误匹配（符号表里没有更近的起点） */
        if (delta > 0x100000ull) return NULL;
        if (out_offset) *out_offset = delta;
        return module->symbols.items[found].name;
    }
}

#define KEEL_MAX_TARGET_MODULES 128
#define KEEL_MAX_THREADS 64
#define KEEL_THREAD_STACK_WINDOW 16384u
#define KEEL_THREAD_HEXDUMP 1024u
#define KEEL_THREAD_FRAMES 32

/* ------------------------------------------------------------------ */
/* C++ 符号还原                                                        */
/* ------------------------------------------------------------------ */

/* libstdc++ 的 abi::__cxa_demangle：看门狗进程里静态链接着一份。 */
typedef char *(*KeelDemangleFn)(const char *mangled, char *buffer, size_t *length, int *status);

static KeelDemangleFn g_demangle_fn = NULL;
static int g_demangle_state = 0; /* 0 未尝试 / 1 不可用 / 2 可用 */

/*
 * 定位当前进程自带的 __cxa_demangle。
 *
 * 它不在 PE 导出表里（只是普通全局符号），所以 Windows 走自身符号表：
 * 自己的加载基址 + RVA；Linux 直接 dlsym 试一次。定位不到就退回原始名字。
 */
static void keel_init_demangler(void) {
    if (g_demangle_state) return;
    g_demangle_state = 1;
#ifdef _WIN32
    {
        KeelTargetModule self;
        HMODULE handle;
        uint64_t base;
        wchar_t wide[KEEL_PATH_MAX];
        memset(&self, 0, sizeof self);
        if (!GetModuleFileNameW(NULL, wide, KEEL_PATH_MAX)) return;
        keel_wide_to_utf8(wide, self.path, sizeof self.path);
        handle = GetModuleHandleW(NULL);
        if (!handle) return;
        base = (uint64_t)(uintptr_t)handle;
        self.start = base;
        self.end = base + keel_pe_image_size((const unsigned char *)(uintptr_t)base);
        if (self.end <= self.start) return;
        keel_module_load_symbols(&self);
        for (int i = 0; i < self.symbols.count; i++) {
            if (strcmp(self.symbols.items[i].name, "__cxa_demangle") == 0) {
                g_demangle_fn = (KeelDemangleFn)(uintptr_t)(base + self.symbols.items[i].rva);
                break;
            }
        }
        keel_symbols_free(&self.symbols);
        if (g_demangle_fn) g_demangle_state = 2;
    }
#else
    {
        void *handle = dlopen(NULL, RTLD_NOW);
        if (!handle) return;
        g_demangle_fn = (KeelDemangleFn)dlsym(handle, "__cxa_demangle");
        dlclose(handle);
        if (g_demangle_fn) g_demangle_state = 2;
    }
#endif
}

/** 把 `_Z...` 还原成 C++ 声明；不是 Itanium 名字或还原失败时返回原串。 */
static const char *keel_pretty_symbol(const char *raw, char *buffer, size_t capacity) {
    char *demangled;
    int status = -1;
    if (!raw || !raw[0] || !buffer || capacity < 32) return raw;
    if (raw[0] != '_' || raw[1] != 'Z') return raw;
    keel_init_demangler();
    if (!g_demangle_fn) return raw;
    demangled = g_demangle_fn(raw, NULL, NULL, &status);
    if (status != 0 || !demangled) return raw;
    {
        /* lambda / template 叠起来的名字会很长，截断避免淹没报告 */
        size_t length = strlen(demangled);
        size_t limit = capacity - 1;
        if (limit > 240) limit = 240;
        if (length <= limit) {
            memcpy(buffer, demangled, length + 1);
        } else {
            memcpy(buffer, demangled, limit - 3);
            memcpy(buffer + limit - 3, "...", 4);
        }
    }
    free(demangled);
    return buffer;
}

/* 对外的符号还原入口：上层（Kotlin 日志）只关心“转换成功没有”。 */
int keel_symbol_demangle(const char *mangled, char *out, uint32_t capacity) {
    char buffer[1024];
    const char *pretty;
    if (!mangled || !out || capacity < 2) return 0;
    pretty = keel_pretty_symbol(mangled, buffer, sizeof buffer);
    if (pretty == mangled) return 0;
    keel_copy(out, capacity, pretty);
    return 1;
}

/* 运行时地址 → 模块名 + 模块内偏移，跨平台各用各的 API。 */
int keel_locate_address(uint64_t address, char *module, uint32_t capacity, uint64_t *rva) {
    if (!address || !module || !capacity) return 0;
#ifdef _WIN32
    {
        HMODULE handle = NULL;
        wchar_t wide[KEEL_PATH_MAX];
        char path[KEEL_PATH_MAX];
        const char *base_name;
        if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                (LPCWSTR)(uintptr_t)address, &handle) || !handle) {
            return 0;
        }
        if (!GetModuleFileNameW(handle, wide, KEEL_PATH_MAX)) return 0;
        keel_wide_to_utf8(wide, path, sizeof path);
        base_name = strrchr(path, '\\');
        keel_copy(module, (size_t)capacity, base_name ? base_name + 1 : path);
        if (rva) *rva = address - (uint64_t)(uintptr_t)handle;
        return 1;
    }
#else
    {
        Dl_info info;
        const char *base_name;
        if (!dladdr((void *)(uintptr_t)address, &info) || !info.dli_fname) return 0;
        base_name = strrchr(info.dli_fname, '/');
        keel_copy(module, (size_t)capacity, base_name ? base_name + 1 : info.dli_fname);
        if (rva) *rva = address - (uint64_t)(uintptr_t)info.dli_fbase;
        return 1;
    }
#endif
}


/* 去掉 C++ 模板参数 `<...>`（支持嵌套）：报告只看函数本体，模板实参没意义。 */
static void keel_drop_templates(char *text) {
    char *read = text;
    char *write = text;
    int depth = 0;
    for (; *read; read++) {
        if (*read == '<') {
            depth++;
            continue;
        }
        if (*read == '>' && depth) {
            depth--;
            continue;
        }
        if (!depth) *write++ = *read;
    }
    *write = '\0';
}

/* `kfun:a.b.C#m(params){}ret` → `a.b.C.m`，与 Kotlin 侧日志保持同一种写法。 */
static void keel_pretty_kfun(char *text) {
    size_t length;
    const char suffix[] = ".internal";
    char *cut;
    char *cursor;
    if (strncmp(text, "kfun:", 5) != 0) return;
    memmove(text, text + 5, strlen(text + 5) + 1);
    cut = strpbrk(text, "({");
    if (cut) *cut = '\0';
    for (cursor = text; *cursor; cursor++) {
        if (*cursor == '#') *cursor = '.';
    }
    length = strlen(text);
    if (length > sizeof suffix - 1 &&
        strcmp(text + length - (sizeof suffix - 1), suffix) == 0) {
        text[length - (sizeof suffix - 1)] = '\0';
    }
    /* kfun 名里也可能带泛型参数 */
    keel_drop_templates(text);
}

/* `_<hex>_N_knbridgeM`：hex 段是「模块坐标 + 源文件路径」，还原成 `包.类.<knbridge>`。 */
static int keel_hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return c - 'A' + 10;
}

static int keel_is_hex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

static void keel_pretty_bridge(char *text) {
    const char *tail;
    const char *cursor;
    const char *marker;
    size_t hex_length;
    size_t index;
    size_t out = 0;
    char decoded[1024];

    if (text[0] != '_' || strncmp(text, "_Z", 2) == 0) return;
    tail = strstr(text, "_knbridge");
    if (!tail) return;
    cursor = text + 1;
    while (cursor < tail && keel_is_hex(*cursor)) cursor++;
    hex_length = (size_t)(cursor - (text + 1));
    if (!hex_length || hex_length % 2 != 0 || hex_length / 2 >= sizeof decoded) return;
    if (cursor < tail && *cursor != '_') return;
    for (index = 0; index < hex_length / 2; index++) {
        decoded[index] = (char)((keel_hex_value(text[1 + index * 2]) << 4) |
                                keel_hex_value(text[2 + index * 2]));
    }
    decoded[index] = '\0';

    marker = strstr(decoded, "/kotlin/");
    marker = marker ? marker + 8 : decoded;
    while (*marker && out + 16 < sizeof decoded) {
        text[out++] = *marker == '/' ? '.' : *marker;
        marker++;
    }
    text[out] = '\0';
    if (out > 3 && strcmp(text + out - 3, ".kt") == 0) {
        out -= 3;
        text[out] = '\0';
    }
    strcat(text, ".<knbridge>");
}

/* 一行帧：符号与偏移都放在同一列（模块名对齐 28 列）。 */
static void keel_print_frame(FILE *out, const KeelTargetModule *module, uint64_t module_offset,
                             const char *symbol) {
    if (symbol && *symbol) {
        fprintf(out, "    at %-28s%s", module->name, symbol);
    } else {
        fprintf(out, "    at %-28s0x%llx", module->name, (unsigned long long)module_offset);
    }
}

static KeelTargetModule *keel_target_module_of(KeelTargetModule *modules, int count,
                                               uint64_t address) {
    for (int i = 0; i < count; i++) {
        if (address >= modules[i].start && address < modules[i].end) return &modules[i];
    }
    return NULL;
}

/*
 * 栈内存扫描：把落在模块地址范围内的 8 字节字当作候选返回地址。
 *
 * 不做 DWARF / .pdata 展开，所以不保证是精确的调用栈；作用是把「这个线程当时
 * 在哪里、被谁调用」的线索保留下来，具体符号离线用 llvm-symbolizer 还原。
 */
static void keel_dump_candidates(FILE *out, const unsigned char *stack, size_t size,
                                 KeelTargetModule *modules, int count) {
    int printed = 0;
    uint64_t previous = 0;
    int has_previous = 0;
    for (size_t offset = 0; offset + 8 <= size && printed < KEEL_THREAD_FRAMES; offset += 8) {
        uint64_t value = 0;
        KeelTargetModule *module;
        uint64_t symbol_offset = 0;
        const char *symbol;
        memcpy(&value, stack + offset, sizeof value);
        module = keel_target_module_of(modules, count, value);
        if (!module) continue;
        /* 同一地址连续出现（递归帧 / 重复保存）只保留一次 */
        if (has_previous && value == previous) continue;
        previous = value;
        has_previous = 1;
        symbol = keel_module_symbolize(module, value, &symbol_offset);
        if (symbol) {
            char pretty[2048];
            char plain[2048];
            keel_copy(plain, sizeof plain, keel_pretty_symbol(symbol, pretty, sizeof pretty));
            if (strncmp(plain, "kfun:", 5) == 0) {
                keel_pretty_kfun(plain);
            } else if (strstr(plain, "_knbridge")) {
                keel_pretty_bridge(plain);
            } else {
                keel_drop_templates(plain);
            }
            keel_print_frame(out, module, value - module->start, plain);
        } else {
            keel_print_frame(out, module, value - module->start, NULL);
        }
        fprintf(out, "  (stack+0x%zx)\n", offset);
        printed++;
    }
    if (!printed) fputs("    (栈里没有落在模块范围内的候选返回地址)\n", out);
}

/* stack 为 NULL 表示这个线程的栈读不到（权限或地址失效）。 */
static void keel_dump_thread_stack(FILE *out, uint64_t tid, int crashing, uint64_t pc, uint64_t sp,
                                   uint64_t bp, const unsigned char *stack, size_t size,
                                   uint64_t base, KeelTargetModule *modules, int count) {
    fprintf(out, "\n线程 %llu%s\n", (unsigned long long)tid, crashing ? "（崩溃线程）" : "");
    fprintf(out, "  rip=0x%016llx  rsp=0x%016llx  rbp=0x%016llx\n", (unsigned long long)pc,
            (unsigned long long)sp, (unsigned long long)bp);
    if (crashing) {
        fputs("  (崩溃线程的 rip 是处理器冻结后的位置；崩溃瞬间的 PC/SP/BP 见报告头)\n", out);
    }
    if (!stack || !size) {
        fputs("  (读不到该线程的栈内存)\n", out);
        return;
    }
    fputs("  候选帧:\n", out);
    keel_dump_candidates(out, stack, size, modules, count);
    {
        size_t dump = crashing ? KEEL_THREAD_HEXDUMP : (KEEL_THREAD_HEXDUMP / 4);
        if (dump > size) dump = size;
        fputs("  栈顶:\n", out);
        keel_hexdump(out, stack, dump, base);
    }
}

/* 崩溃瞬间的 PC 归属：模块 + 偏移，能解析到符号就一并写出来。 */
static void keel_dump_crash_site(FILE *out, const KeelCrashRecord *rec, KeelTargetModule *modules,
                                 int count) {
    KeelTargetModule *owner = NULL;
    uint64_t offset = 0;
    const char *symbol;

    if (!rec->module_base || !rec->pc) return;
    for (int i = 0; i < count; i++) {
        if (modules[i].start == rec->module_base) {
            owner = &modules[i];
            break;
        }
    }
    if (!owner) return;
    fputs("\n崩溃位置    : ", out);
    if (rec->pc >= owner->start) {
        symbol = keel_module_symbolize(owner, rec->pc, &offset);
        if (symbol) {
            char pretty[2048];
            char plain[2048];
            keel_copy(plain, sizeof plain, keel_pretty_symbol(symbol, pretty, sizeof pretty));
            if (strncmp(plain, "kfun:", 5) == 0) {
                keel_pretty_kfun(plain);
            } else if (strstr(plain, "_knbridge")) {
                keel_pretty_bridge(plain);
            } else {
                keel_drop_templates(plain);
            }
            fprintf(out, "%-28s%s", owner->name, plain);
        } else {
            fprintf(out, "%-28s0x%llx", owner->name,
                    (unsigned long long)(rec->pc - owner->start));
        }
    } else {
        fprintf(out, "%-28s0x0", owner->name);
    }
    fputc('\n', out);
}

/* 平台相关的内存转储：模块列表 + 每个线程的寄存器、候选帧与栈。 */
#ifdef _WIN32
static void keel_dump_process(FILE *out, uint64_t pid, const KeelCrashRecord *rec) {
    HANDLE process;
    HANDLE threads;
    THREADENTRY32 thread;
    HANDLE modules;
    MODULEENTRY32W module;
    KeelTargetModule *targets;
    int target_count = 0;
    DWORD tids[KEEL_MAX_THREADS];
    HANDLE handles[KEEL_MAX_THREADS];
    int thread_count = 0;
    unsigned char *stack;

    process = OpenProcess(
        PROCESS_VM_READ | PROCESS_QUERY_INFORMATION | PROCESS_SUSPEND_RESUME | PROCESS_TERMINATE,
        FALSE, (DWORD)pid);
    if (!process) {
        fprintf(out, "(无法打开进程 %llu：error=%lu)\n", (unsigned long long)pid, GetLastError());
        return;
    }

    targets = (KeelTargetModule *)calloc(KEEL_MAX_TARGET_MODULES, sizeof *targets);

    fputs("\n--------------- 已加载模块 ---------------\n", out);
    modules = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, (DWORD)pid);
    if (modules != INVALID_HANDLE_VALUE) {
        memset(&module, 0, sizeof module);
        module.dwSize = sizeof module;
        if (Module32FirstW(modules, &module)) {
            do {
                uint64_t start = (uint64_t)(uintptr_t)module.modBaseAddr;
                uint64_t end = start + module.modBaseSize;
                char module_name[KEEL_MODULE_NAME];
                keel_wide_to_utf8(module.szModule, module_name, sizeof module_name);
                fprintf(out, "%016llx-%016llx  %s\n", (unsigned long long)start,
                        (unsigned long long)end, module_name);
                if (targets && target_count < KEEL_MAX_TARGET_MODULES) {
                    KeelTargetModule *entry = &targets[target_count++];
                    entry->start = start;
                    entry->end = end;
                    keel_copy(entry->name, sizeof entry->name, module_name);
                    keel_wide_to_utf8(module.szExePath, entry->path, sizeof entry->path);
                }
            } while (Module32NextW(modules, &module));
        }
        CloseHandle(modules);
    } else {
        fputs("(模块枚举失败)\n", out);
    }

    keel_dump_crash_site(out, rec, targets, target_count);

    /* 先挂起全部线程，再逐个取上下文，保证读到的栈是同一时刻的 */
    threads = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    if (threads != INVALID_HANDLE_VALUE) {
        memset(&thread, 0, sizeof thread);
        thread.dwSize = sizeof thread;
        if (Thread32First(threads, &thread)) {
            do {
                HANDLE handle;
                if (thread.th32OwnerProcessID != (DWORD)pid) continue;
                if (thread_count >= KEEL_MAX_THREADS) break;
                handle = OpenThread(
                    THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT | THREAD_QUERY_INFORMATION, FALSE,
                    thread.th32ThreadID);
                if (!handle) continue;
                SuspendThread(handle);
                tids[thread_count] = thread.th32ThreadID;
                handles[thread_count] = handle;
                thread_count++;
            } while (Thread32Next(threads, &thread));
        }
        CloseHandle(threads);
    }

    fputs("\n--------------- 线程栈 ---------------\n", out);
    fprintf(out, "(共 %d 个线程；帧由栈内存扫描得到，符号取自各模块自身的符号表)\n", thread_count);

    stack = (unsigned char *)malloc(KEEL_THREAD_STACK_WINDOW);
    for (int i = 0; i < thread_count; i++) {
        CONTEXT context;
        uint64_t pc = 0;
        uint64_t sp = 0;
        uint64_t bp = 0;
        uint64_t base = 0;
        SIZE_T got = 0;
        int readable = 0;

        memset(&context, 0, sizeof context);
        context.ContextFlags = CONTEXT_CONTROL | CONTEXT_INTEGER;
        if (GetThreadContext(handles[i], &context)) {
            pc = (uint64_t)context.Rip;
            sp = (uint64_t)context.Rsp;
            bp = (uint64_t)context.Rbp;
        }

        if (stack && sp) {
            base = sp > 512u ? sp - 512u : 0;
            readable = ReadProcessMemory(process, (LPCVOID)(uintptr_t)base, stack,
                                         KEEL_THREAD_STACK_WINDOW, &got)
                           ? 1
                           : got > 0;
        }
        keel_dump_thread_stack(out, (uint64_t)tids[i], (uint32_t)tids[i] == rec->tid, pc, sp, bp,
                               readable ? stack : NULL, readable ? (size_t)got : 0u, base, targets,
                               target_count);
    }
    free(stack);
    for (int i = 0; i < thread_count; i++) CloseHandle(handles[i]);
    for (int i = 0; i < target_count; i++) keel_symbols_free(&targets[i].symbols);
    free(targets);
    CloseHandle(process);
}

static void keel_kill_process(uint64_t pid) {
    HANDLE process = OpenProcess(PROCESS_TERMINATE, FALSE, (DWORD)pid);
    if (!process) return;
    TerminateProcess(process, 2);
    CloseHandle(process);
}
#else
/* 解析一行 /proc/<pid>/maps；name 指向原始行里的路径（可能带换行）。 */
static int keel_parse_maps(char *line, uint64_t *start, uint64_t *end, int *executable,
                           char **name) {
    char *cursor = line;
    char *stop = NULL;
    unsigned long long begin = strtoull(cursor, &stop, 16);
    unsigned long long finish;
    if (!stop || *stop != '-') return 0;
    finish = strtoull(stop + 1, &cursor, 16);
    if (!begin || finish <= begin) return 0;
    while (*cursor == ' ') cursor++;
    if (strlen(cursor) < 3) return 0;
    *executable = cursor[2] == 'x';
    /* 跳过 perms / offset / dev / inode 四个字段 */
    for (int field = 0; field < 4; field++) {
        while (*cursor == ' ') cursor++;
        while (*cursor && *cursor != ' ') cursor++;
    }
    while (*cursor == ' ') cursor++;
    *start = begin;
    *end = finish;
    *name = cursor;
    return 1;
}

/* 读一个线程的通用寄存器；失败返回 0。 */
static int keel_thread_registers(pid_t tid, uint64_t *pc, uint64_t *sp, uint64_t *bp) {
    struct iovec iov;
#if defined(__x86_64__)
    struct {
        unsigned long long r15, r14, r13, r12, rbp, rbx, r11, r10, r9, r8, rax, rcx, rdx, rsi, rdi,
            orig_rax, rip, cs, eflags, rsp, ss, fs_base, gs_base, ds, es, fs, gs;
    } regs;
    iov.iov_base = &regs;
    iov.iov_len = sizeof regs;
    if (ptrace(PTRACE_GETREGSET, tid, (void *)NT_PRSTATUS, &iov) != 0) return 0;
    *pc = regs.rip;
    *sp = regs.rsp;
    *bp = regs.rbp;
#elif defined(__aarch64__)
    struct {
        unsigned long long regs[31];
        unsigned long long sp;
        unsigned long long pc;
        unsigned long long pstate;
    } state;
    iov.iov_base = &state;
    iov.iov_len = sizeof state;
    if (ptrace(PTRACE_GETREGSET, tid, (void *)NT_PRSTATUS, &iov) != 0) return 0;
    *pc = state.pc;
    *sp = state.sp;
    *bp = state.regs[29];
#else
    (void)tid;
    (void)pc;
    (void)sp;
    (void)bp;
    return 0;
#endif
    return 1;
}

static void keel_dump_process(FILE *out, uint64_t pid, const KeelCrashRecord *rec) {
    char path[64];
    FILE *maps;
    char line[1024];
    KeelTargetModule *targets;
    int target_count = 0;
    pid_t tids[KEEL_MAX_THREADS];
    int thread_count = 0;
    unsigned char *stack;
    int mem_fd;
    int status;

    if (ptrace(PTRACE_ATTACH, (pid_t)pid, NULL, NULL) != 0) {
        fprintf(out, "(ptrace attach 失败：%s；报告只含崩溃处理器记录的现场)\n", strerror(errno));
        return;
    }
    waitpid((pid_t)pid, &status, __WALL);

    targets = (KeelTargetModule *)calloc(KEEL_MAX_TARGET_MODULES, sizeof *targets);

    fputs("\n--------------- 地址空间 ---------------\n", out);
    snprintf(path, sizeof path, "/proc/%llu/maps", (unsigned long long)pid);
    maps = fopen(path, "rb");
    if (maps) {
        while (fgets(line, sizeof line, maps)) {
            uint64_t start = 0, end = 0;
            int executable = 0;
            char *name = NULL;
            fputs(line, out);
            if (!keel_parse_maps(line, &start, &end, &executable, &name)) continue;
            if (!executable || !name || !*name || *name == '\n') continue;
            if (!targets || target_count >= KEEL_MAX_TARGET_MODULES) continue;
            {
                KeelTargetModule *entry = &targets[target_count++];
                size_t length;
                entry->start = start;
                entry->end = end;
                keel_copy(entry->name, sizeof entry->name, keel_basename(name));
                length = strlen(entry->name);
                while (length && (entry->name[length - 1] == '\n' || entry->name[length - 1] == '\r')) {
                    entry->name[--length] = '\0';
                }
                keel_copy(entry->path, sizeof entry->path, name);
                length = strlen(entry->path);
                while (length && (entry->path[length - 1] == '\n' || entry->path[length - 1] == '\r')) {
                    entry->path[--length] = '\0';
                }
                /* 模块被替换 / 删除时 maps 会带上这个后缀，去掉才能打开文件 */
                {
                    char *suffix = strstr(entry->path, " (deleted)");
                    if (suffix) *suffix = '\0';
                }
            }
        }
        fclose(maps);
    } else {
        fputs("(无法读取地址空间)\n", out);
    }

    keel_dump_crash_site(out, rec, targets, target_count);

    /* 逐个 attach 线程，把整个进程停住 */
    {
        char task_dir[64];
        DIR *dir;
        struct dirent *entry;
        snprintf(task_dir, sizeof task_dir, "/proc/%llu/task", (unsigned long long)pid);
        dir = opendir(task_dir);
        if (dir) {
            while ((entry = readdir(dir)) != NULL && thread_count < KEEL_MAX_THREADS) {
                pid_t tid = (pid_t)strtol(entry->d_name, NULL, 10);
                if (tid <= 0) continue;
                if (tid != (pid_t)pid && ptrace(PTRACE_ATTACH, tid, NULL, NULL) == 0) {
                    waitpid(tid, &status, __WALL);
                }
                tids[thread_count++] = tid;
            }
            closedir(dir);
        }
    }
    if (!thread_count) {
        tids[thread_count++] = (pid_t)pid;
    }

    fputs("\n--------------- 线程栈 ---------------\n", out);
    fprintf(out, "(共 %d 个线程；帧由栈内存扫描得到，符号取自各模块自身的符号表)\n", thread_count);

    snprintf(path, sizeof path, "/proc/%llu/mem", (unsigned long long)pid);
    mem_fd = open(path, O_RDONLY);
    stack = (unsigned char *)malloc(KEEL_THREAD_STACK_WINDOW);
    for (int i = 0; i < thread_count; i++) {
        uint64_t pc = 0, sp = 0, bp = 0, base = 0;
        size_t got = 0;
        unsigned char *buffer = NULL;

        keel_thread_registers(tids[i], &pc, &sp, &bp);
        if (stack && sp && mem_fd >= 0) {
            base = sp > 512u ? sp - 512u : 0;
            {
                ssize_t n = pread(mem_fd, stack, KEEL_THREAD_STACK_WINDOW, (off_t)base);
                if (n > 0) {
                    got = (size_t)n;
                    buffer = stack;
                }
            }
        }
        keel_dump_thread_stack(out, (uint64_t)tids[i], (uint32_t)tids[i] == rec->tid, pc, sp, bp,
                               buffer, got, base, targets, target_count);
    }
    if (mem_fd >= 0) close(mem_fd);
    free(stack);
    for (int i = 0; i < target_count; i++) keel_symbols_free(&targets[i].symbols);
    free(targets);
}

static void keel_kill_process(uint64_t pid) {
    kill((pid_t)pid, SIGKILL);
}
#endif

static void keel_write_report(uint64_t pid, const KeelCrashRecord *rec, const char *log_dir,
                              char *report_path, size_t report_cap) {
    char stamp[32];
    char dir[KEEL_PATH_MAX];
    char signal_text[64];
    FILE *out;
    const char *module = rec->module[0] ? rec->module : "unknown";
    int is_host = strcmp(module, "keel-host") == 0;
    int y, mo, d, h, mi, s, ms;

    snprintf(dir, sizeof dir, "%s/crash", log_dir);
    keel_mkdirs(dir);
    keel_local_parts(&y, &mo, &d, &h, &mi, &s, &ms);
    snprintf(stamp, sizeof stamp, "%04d%02d%02d-%02d%02d%02d-%03d", y, mo, d, h, mi, s, ms);
    snprintf(report_path, report_cap, "%s/keel-crash-%s.log", dir, stamp);

    out = keel_open_utf8(report_path, "wb");
    if (!out) return;

    keel_signal_name(rec->signal, signal_text, sizeof signal_text);

    fprintf(out,
            "================================================================\n"
            "Keel 崩溃报告\n"
            "================================================================\n");
    {
        char datetime[64];
        keel_datetime_string(datetime, sizeof datetime);
        fprintf(out, "时间        : %s\n", datetime);
    }
    fprintf(out, "构建        : %s\n", rec->build[0] ? rec->build : "unknown");
    fprintf(out, "进程        : %llu\n", (unsigned long long)pid);
    fprintf(out, "线程        : %u\n", rec->tid);
    fprintf(out, "信号        : %s\n", signal_text);
    fprintf(out, "故障地址    : 0x%016llx\n", (unsigned long long)rec->fault_address);
    fprintf(out, "指令指针    : 0x%016llx\n", (unsigned long long)rec->pc);
    fprintf(out, "栈指针      : 0x%016llx\n", (unsigned long long)rec->sp);
    fprintf(out, "帧指针      : 0x%016llx\n\n", (unsigned long long)rec->bp);

    fputs("--------------- 结论 ---------------\n", out);
    if (rec->module[0]) {
        fprintf(out, "崩溃发生在%s `%s` 的代码段内", is_host ? " Host" : "插件", module);
        if (rec->module_base && rec->pc >= rec->module_base) {
            fprintf(out, "（+0x%llx）", (unsigned long long)(rec->pc - rec->module_base));
        }
        fputs("。\n", out);
        if (!is_host) {
            fprintf(out,
                    "下一次启动应把该插件计入崩溃计数；连续崩溃达到阈值时自动禁用（不进加载列表）。\n");
        }
    } else {
        fputs("故障地址不在已登记的模块范围内（可能来自外部库、JIT 代码或已损坏的指针）。\n", out);
    }

    fputs("\n--------------- 已登记模块 ---------------\n", out);
    if (rec->module[0]) {
        /* 看门狗进程看不到 Host 的模块表，只能从崩溃记录里带回来的那一条还原 */
        fprintf(out, "%016llx  %s\n", (unsigned long long)rec->module_base, rec->module);
    } else {
        fputs("(崩溃记录里没有模块信息)\n", out);
    }
    fputs("完整模块列表见下面的「已加载模块」；地址归属 = 模块基址 + RVA，\n"
          "能解析到符号时已附在该行末尾（取自模块文件自身的 kfun: 符号表）。\n",
          out);

    keel_dump_process(out, pid, rec);

    fputs("\n--------------- 最近日志 ---------------\n", out);
    keel_dump_log_tail(out, log_dir);

    fputs("\n================================================================\n", out);
    fclose(out);
}

static void keel_append_log_summary(const char *log_dir, const KeelCrashRecord *rec,
                                    const char *report_path) {
    char line[1024];
    char clock[16];
    char signal_text[64];
    KeelLogFile *log;
    int length;
    int y, mo, d, h, mi, s, ms;

    keel_local_parts(&y, &mo, &d, &h, &mi, &s, &ms);
    snprintf(clock, sizeof clock, "%02d:%02d:%02d", h, mi, s);
    keel_signal_name(rec->signal, signal_text, sizeof signal_text);
    log = keel_logfile_open(log_dir);
    if (!log) return;

    length = snprintf(line, sizeof line, "%s ERROR %-15s %-40s : 进程 %u 崩溃（%s），现场已冻结\n",
                      clock, "WATCHDOG", "-", rec->pid, signal_text);
    if (length > 0) keel_logfile_write(log, line, (uint32_t)length);

    length = snprintf(line, sizeof line, "%s ERROR %-15s %-40s : 归因：%s%s\n", clock, "WATCHDOG", "-",
                      rec->module[0] ? rec->module : "unknown",
                      strcmp(rec->module, "keel-host") == 0 ? "（Host 自身）" : "（插件）");
    if (length > 0) keel_logfile_write(log, line, (uint32_t)length);

    length = snprintf(line, sizeof line, "%s ERROR %-15s %-40s : 报告：%s\n", clock, "WATCHDOG", "-",
                      report_path);
    if (length > 0) keel_logfile_write(log, line, (uint32_t)length);

    keel_logfile_close(log);
}

int keel_watchdog_watch(uint64_t parent_pid) {
    char cwd[KEEL_PATH_MAX];
    char log_dir[KEEL_PATH_MAX];
    char beacon[KEEL_PATH_MAX];
    KeelCrashRecord rec;

    if (!parent_pid) return 0;
    if (keel_cwd(cwd, sizeof cwd) != 0) return 0;
    snprintf(log_dir, sizeof log_dir, "%s/work/log", cwd);
    snprintf(beacon, sizeof beacon, "%s/crash/watchdog-%llu.bin", log_dir,
             (unsigned long long)parent_pid);

    for (;;) {
        if (keel_read_crash(beacon, &rec)) {
            char report[KEEL_PATH_MAX];
            keel_write_report(parent_pid, &rec, log_dir, report, sizeof report);
            keel_append_log_summary(log_dir, &rec, report);
            printf("watchdog: crash captured, report: %s\n", report);
            fflush(stdout);
            keel_kill_process(parent_pid);
            return 1;
        }
        if (!keel_parent_alive(parent_pid)) {
            /* 退出前再确认一次，避免漏掉刚好写完的崩溃记录 */
            if (keel_read_crash(beacon, &rec)) {
                char report[KEEL_PATH_MAX];
                keel_write_report(parent_pid, &rec, log_dir, report, sizeof report);
                keel_append_log_summary(log_dir, &rec, report);
                return 1;
            }
            /* 正常退出：清掉空的 beacon，不留下每次启动一个文件 */
            keel_remove_utf8(beacon);
            return 0;
        }
        keel_sleep_ms(50);
    }
}
