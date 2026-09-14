/*
 * mf-doctor — the first binary MobileForge ships and runs as its own toolchain.
 *
 * Its purpose is verification, not utility. Bundling PHP or Node is a build-system project
 * (ADR-011); before investing in that, we need proof that the whole chain actually works on a
 * device:
 *
 *     download -> SHA-256 verify -> extract into $PREFIX -> system-linker exec -> terminal
 *
 * So this is a real, dynamically-linked ELF built with the NDK against our own prefix, and it
 * reports exactly the facts that would be wrong if any link in that chain were broken:
 *
 *   - argv[0], which under system-linker exec is the program, not the linker
 *   - $PREFIX and $HOME, proving the environment builder ran
 *   - whether stdin is a TTY, proving it came through the PTY and not a pipe
 *   - the page size, which is what makes 16 KB-page devices fail if alignment is wrong
 *
 * If `mf-doctor` prints sensible values in the app's terminal, the toolchain mechanism is
 * sound and the remaining work is packaging real software rather than solving Android.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *value_or(const char *name, const char *fallback) {
    const char *value = getenv(name);
    return (value != NULL && value[0] != '\0') ? value : fallback;
}

int main(int argc, char **argv) {
    /* --version keeps it usable as a probe target for RuntimeCapabilityProbe. */
    if (argc > 1 && strcmp(argv[1], "--version") == 0) {
        printf("mf-doctor 1.0.0\n");
        return 0;
    }

    printf("mf-doctor 1.0.0 — MobileForge toolchain self-check\n");
    printf("\n");

    printf("  argv[0]        %s\n", argv[0]);

    /*
     * Under system-linker exec the kernel runs /system/bin/linker64, so /proc/self/exe points
     * at the linker rather than at this program. termux-exec's convention is to carry the real
     * path in an environment variable, which our RuntimeEnvironmentBuilder sets.
     */
    printf("  real path      %s\n", value_or("TERMUX_EXEC__PROC_SELF_EXE", "(not set)"));

    printf("  PREFIX         %s\n", value_or("PREFIX", "(not set)"));
    printf("  HOME           %s\n", value_or("HOME", "(not set)"));
    printf("  PATH           %s\n", value_or("PATH", "(not set)"));
    printf("  TERM           %s\n", value_or("TERM", "(not set)"));
    printf("  cwd            %s\n", value_or("PWD", "(not set)"));

    printf("\n");
    printf("  stdin is a tty %s\n", isatty(STDIN_FILENO) ? "yes" : "no");
    printf("  page size      %ld bytes\n", sysconf(_SC_PAGESIZE));
    printf("  pid            %d\n", (int) getpid());

    printf("\n");
    printf("  If you can read this, MobileForge installed and executed its own binary.\n");

    return 0;
}
