/*
 * sigsys-shim -- let musl/Bun survive Android's app seccomp filter.
 *
 * Android's seccomp policy for apps is an allowlist. Two families of syscall
 * get answered with SECCOMP_RET_TRAP (a catchable SIGSYS) even though they are
 * perfectly ordinary:
 *
 *   1. Recent syscalls the allowlist predates -- e.g. close_range(2). Bun calls
 *      close_range during startup, so it dies with "Bad system call" before it
 *      prints anything.
 *
 *   2. Legacy path syscalls (open/stat/access/mkdir/unlink/rename/...). Android
 *      expects native code to use the *at() variants, so the plain ones are not
 *      on the allowlist. musl and Zig call the plain ones.
 *
 * The filter cannot be removed (seccomp is inherited and can only be tightened),
 * but SECCOMP_RET_TRAP raises a *catchable* SIGSYS. This shim is preloaded into
 * the musl process by the launcher; its constructor installs a SIGSYS handler
 * that:
 *
 *   - forwards a trapped legacy syscall to its allowed *at() equivalent and
 *     writes the real result back to the return register;
 *   - neuters close_range to "success" (it only sets CLOEXEC on high fds);
 *   - answers every other trapped syscall with -ENOSYS, exactly what a kernel
 *     without that syscall would report, so runtimes fall back cleanly.
 *
 * The handler leaves the program counter where the kernel put it (x86_64/aarch64
 * save the address *after* the trapping instruction) and returns; the process
 * continues normally.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

#define MF_AT_FDCWD (-100)
#define MF_AT_SYMLINK_NOFOLLOW 0x100
#define MF_AT_REMOVEDIR 0x200

#define MF_O_RDONLY 0
#define MF_O_WRONLY 1
#define MF_O_CREAT 0100
#define MF_O_TRUNC 01000
#define MF_O_DIRECTORY 0200000

/* Raw syscall so we see the kernel's own return value (negative == -errno). */
__attribute__((unused))
static long mf_raw6(long n, long a0, long a1, long a2, long a3, long a4, long a5)
{
#if defined(__x86_64__)
    long ret;
    register long r10 __asm__("r10") = a3;
    register long r8 __asm__("r8") = a4;
    register long r9 __asm__("r9") = a5;
    __asm__ __volatile__("syscall"
                         : "=a"(ret)
                         : "a"(n), "D"(a0), "S"(a1), "d"(a2),
                           "r"(r10), "r"(r8), "r"(r9)
                         : "rcx", "r11", "memory");
    return ret;
#elif defined(__aarch64__)
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a0;
    register long x1 __asm__("x1") = a1;
    register long x2 __asm__("x2") = a2;
    register long x3 __asm__("x3") = a3;
    register long x4 __asm__("x4") = a4;
    register long x5 __asm__("x5") = a5;
    __asm__ __volatile__("svc #0"
                         : "+r"(x0)
                         : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                         : "memory");
    return x0;
#else
#error "sigsys-shim: unsupported architecture"
#endif
}

/* ---- architecture accessors for the signal context ---- */

typedef struct {
    long nr;
    long a0, a1, a2, a3, a4, a5;
    void *pc;
} mf_frame;

static void mf_frame_load(mf_frame *f, ucontext_t *uc, siginfo_t *si)
{
    f->nr = (long)si->si_syscall;
#if defined(__x86_64__)
    f->a0 = (long)uc->uc_mcontext.gregs[REG_RDI];
    f->a1 = (long)uc->uc_mcontext.gregs[REG_RSI];
    f->a2 = (long)uc->uc_mcontext.gregs[REG_RDX];
    f->a3 = (long)uc->uc_mcontext.gregs[REG_R10];
    f->a4 = (long)uc->uc_mcontext.gregs[REG_R8];
    f->a5 = (long)uc->uc_mcontext.gregs[REG_R9];
    f->pc = (void *)(unsigned long)uc->uc_mcontext.gregs[REG_RIP];
#elif defined(__aarch64__)
    f->a0 = (long)uc->uc_mcontext.regs[0];
    f->a1 = (long)uc->uc_mcontext.regs[1];
    f->a2 = (long)uc->uc_mcontext.regs[2];
    f->a3 = (long)uc->uc_mcontext.regs[3];
    f->a4 = (long)uc->uc_mcontext.regs[4];
    f->a5 = (long)uc->uc_mcontext.regs[5];
    f->pc = (void *)(unsigned long)uc->uc_mcontext.pc;
#endif
}

static void mf_frame_store_result(ucontext_t *uc, siginfo_t *si, long ret)
{
#if defined(__x86_64__)
    uc->uc_mcontext.gregs[REG_RAX] = (greg_t)ret;
    {
        unsigned long rip = (unsigned long)uc->uc_mcontext.gregs[REG_RIP];
        unsigned long call_addr = (unsigned long)si->si_call_addr;
        /* regs->ip is already past the 2-byte `syscall`; only fix odd kernels. */
        if (rip + 2 == call_addr) {
            uc->uc_mcontext.gregs[REG_RIP] = call_addr;
        }
    }
#elif defined(__aarch64__)
    uc->uc_mcontext.regs[0] = (unsigned long)ret;
    {
        unsigned long pc = (unsigned long)uc->uc_mcontext.pc;
        unsigned long call_addr = (unsigned long)si->si_call_addr;
        /* regs->pc is already past the 4-byte `svc #0`; only fix odd kernels. */
        if (pc + 4 == call_addr) {
            uc->uc_mcontext.pc = call_addr;
        }
    }
#endif
}

/* Forward a trapped legacy syscall to an allowed *at() equivalent. */
static long mf_forward(mf_frame *f)
{
#if defined(__x86_64__)
    switch (f->nr) {
    case 2: /* open -> openat */
        return mf_raw6(257, MF_AT_FDCWD, f->a0, f->a1, f->a2, 0, 0);
    case 4: /* stat -> newfstatat */
        return mf_raw6(262, MF_AT_FDCWD, f->a0, f->a1, 0, 0, 0);
    case 6: /* lstat -> newfstatat(AT_SYMLINK_NOFOLLOW) */
        return mf_raw6(262, MF_AT_FDCWD, f->a0, f->a1, MF_AT_SYMLINK_NOFOLLOW, 0, 0);
    case 21: /* access -> faccessat */
        return mf_raw6(269, MF_AT_FDCWD, f->a0, f->a1, 0, 0, 0);
    case 82: /* rename -> renameat */
        return mf_raw6(264, MF_AT_FDCWD, f->a0, MF_AT_FDCWD, f->a1, 0, 0);
    case 83: /* mkdir -> mkdirat */
        return mf_raw6(258, MF_AT_FDCWD, f->a0, f->a1, 0, 0, 0);
    case 84: /* rmdir -> unlinkat(AT_REMOVEDIR) */
        return mf_raw6(263, MF_AT_FDCWD, f->a0, MF_AT_REMOVEDIR, 0, 0, 0);
    case 85: /* creat -> openat */
        return mf_raw6(257, MF_AT_FDCWD, f->a0, MF_O_CREAT | MF_O_WRONLY | MF_O_TRUNC, f->a1, 0, 0);
    case 86: /* link -> linkat */
        return mf_raw6(265, MF_AT_FDCWD, f->a0, MF_AT_FDCWD, f->a1, 0, 0);
    case 87: /* unlink -> unlinkat */
        return mf_raw6(263, MF_AT_FDCWD, f->a0, 0, 0, 0, 0);
    case 88: /* symlink -> symlinkat */
        return mf_raw6(266, f->a0, MF_AT_FDCWD, f->a1, 0, 0, 0);
    case 89: /* readlink -> readlinkat */
        return mf_raw6(267, MF_AT_FDCWD, f->a0, f->a1, f->a2, 0, 0);
    case 90: /* chmod -> fchmodat */
        return mf_raw6(268, MF_AT_FDCWD, f->a0, f->a1, 0, 0, 0);
    case 92: /* chown -> fchownat */
        return mf_raw6(260, MF_AT_FDCWD, f->a0, f->a1, f->a2, 0, 0);
    case 94: /* lchown -> fchownat(AT_SYMLINK_NOFOLLOW) */
        return mf_raw6(260, MF_AT_FDCWD, f->a0, f->a1, f->a2, MF_AT_SYMLINK_NOFOLLOW, 0);
    case 76: { /* truncate -> openat + ftruncate */
        long fd = mf_raw6(257, MF_AT_FDCWD, f->a0, MF_O_WRONLY, 0, 0, 0);
        if (fd < 0) {
            return fd;
        }
        long r = mf_raw6(77, fd, f->a1, 0, 0, 0, 0); /* ftruncate */
        mf_raw6(3, fd, 0, 0, 0, 0, 0);               /* close */
        return r;
    }
    case 7: { /* poll -> ppoll */
        /*
         * Android's app seccomp allowlist predates/omits the plain `poll`, which musl (and
         * therefore Bun) calls directly, so it traps. Without this forward the trapped call
         * returned -ENOSYS and Bun's event loop spun on it, never reaching its first frame.
         * ppoll() differs only by taking a timespec and a signal mask; with a NULL mask the
         * kernel ignores sigsetsize, so it is a drop-in for poll().
         */
        struct timespec ts;
        struct timespec *tp = 0;
        long ms = f->a2;
        if (ms >= 0) {
            ts.tv_sec = ms / 1000;
            ts.tv_nsec = (ms % 1000) * 1000000L;
            tp = &ts;
        }
        return mf_raw6(271, f->a0, f->a1, (long)tp, 0, 0, 0);
    }
    case 132: /* utime -> utimensat (seconds -> timespec omitted; best effort) */
        return -ENOSYS;
    default:
        return -ENOSYS;
    }
#elif defined(__aarch64__)
    /* The aarch64 kernel has no legacy open/stat/access/mkdir/etc. at all; the
     * syscall numbers Bun used on x86_64 do not exist here. Anything trapped on
     * aarch64 is therefore a genuinely-unknown syscall. */
    return -ENOSYS;
#endif
}

/* Async-signal-safe decimal log of a handled trap (stderr).
 *
 * OFF unless MF_SIGSYS_LOG is set. The handler fires for every legacy path syscall, and Bun
 * makes thousands of them while starting up; writing a line each time floods the controlling
 * terminal and scribbles over the escape sequences of a full-screen TUI (OpenCode's included),
 * which is what the user sees as a garbled screen. Keep it available for debugging only. */
static int mf_log_enabled;

static void mf_log(long nr, long ret)
{
    char buf[64];
    int i = 0;
    if (!mf_log_enabled) {
        return;
    }
    static const char prefix[] = "sigsys-shim: syscall ";
    static const char arrow[] = " -> ";
    for (size_t k = 0; k < sizeof(prefix) - 1; k++) {
        buf[i++] = prefix[k];
    }
    char digits[24];
    int d = 0;
    long v = nr < 0 ? -nr : nr;
    if (v == 0) {
        digits[d++] = '0';
    }
    while (v > 0 && d < (int)sizeof(digits)) {
        digits[d++] = (char)('0' + (v % 10));
        v /= 10;
    }
    if (nr < 0) {
        digits[d++] = '-';
    }
    while (d > 0) {
        buf[i++] = digits[--d];
    }
    for (size_t k = 0; k < sizeof(arrow) - 1; k++) {
        buf[i++] = arrow[k];
    }
    long rv = ret < 0 ? -ret : ret;
    d = 0;
    if (rv == 0) {
        digits[d++] = '0';
    }
    while (rv > 0 && d < (int)sizeof(digits)) {
        digits[d++] = (char)('0' + (rv % 10));
        rv /= 10;
    }
    if (ret < 0) {
        digits[d++] = '-';
    }
    while (d > 0) {
        buf[i++] = digits[--d];
    }
    buf[i++] = '\n';
    ssize_t ignored = write(2, buf, (size_t)i);
    (void)ignored;
}

static __thread int mf_in_handler;

static void mf_sigsys_handler(int sig, siginfo_t *si, void *ucontext_ptr)
{
    if (si != NULL && si->si_code == SYS_SECCOMP) {
        mf_frame f;
        mf_frame_load(&f, (ucontext_t *)ucontext_ptr, si);

        long ret;
        if (mf_in_handler) {
            /* Re-entrant trap: a forwarded *at() call is also blocked. Bail out
             * instead of looping forever. */
            ret = -ENOSYS;
        } else if (f.nr == 436) {
            /* close_range: only sets CLOEXEC on high fds; pretend it worked. */
            ret = 0;
        } else {
            mf_in_handler = 1;
            ret = mf_forward(&f);
            mf_in_handler = 0;
        }

        mf_log(f.nr, ret);
        mf_frame_store_result((ucontext_t *)ucontext_ptr, si, ret);
        return;
    }

    /* Not a seccomp trap: restore the default action and let it happen. */
    signal(sig, SIG_DFL);
    raise(sig);
}

__attribute__((constructor))
static void mf_install_sigsys_handler(void)
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = mf_sigsys_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSYS, &sa, NULL);
    mf_log_enabled = getenv("MF_SIGSYS_LOG") != NULL;
}
