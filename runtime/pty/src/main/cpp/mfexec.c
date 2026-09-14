/*
 * libmfexec — an execve() interposer, loaded via LD_PRELOAD.
 *
 * WHY THIS IS NECESSARY
 *
 * ExecCommandBuilder (ADR-009) rewrites a program path into
 * `/system/bin/linker64 <path> args...` so that W^X does not block it. That works perfectly —
 * for processes *we* launch.
 *
 * It does nothing for processes launched by anything else. Once a shell is running in the
 * terminal, every command the user types is exec'd by the SHELL, through the kernel's normal
 * path, and SELinux refuses it:
 *
 *     /system/bin/sh: /data/.../usr/bin/mf-doctor: Permission denied
 *
 * That was observed on a real device, not predicted. A toolchain you can install but cannot
 * invoke from a shell is not a toolchain.
 *
 * The fix is to intervene one level lower: preload a library into every process that overrides
 * execve(), so the rewrite happens no matter who calls it. This is the same approach
 * termux-exec takes, and for the same reason.
 *
 * SCOPE AND LIMITS (deliberate)
 *
 *   - Only paths under $MOBILEFORGE_PREFIX are rewritten. System binaries are left completely
 *     alone: they already execute fine, and routing them through the linker would break
 *     /proc/self/exe for no benefit.
 *   - Shebang scripts are resolved here too, because the kernel never sees the script when we
 *     hand it the linker instead.
 *   - Static ELF binaries are passed through unmodified. The linker cannot load them, so
 *     rewriting would turn a clear "Permission denied" into a confusing linker error.
 *   - posix_spawn is NOT intercepted. bionic implements it via its own syscall path rather
 *     than through execve, so it would need separate handling; nothing in our toolchain uses
 *     it today. Documented rather than silently missing.
 */

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define MF_EXPORT __attribute__((visibility("default")))

#define MAX_ARGS 4096
#define HEADER_BYTES 160
#define SHEBANG_MAX 127

#if defined(__LP64__)
#define SYSTEM_LINKER "/system/bin/linker64"
#else
#define SYSTEM_LINKER "/system/bin/linker"
#endif

typedef int (*execve_fn)(const char *, char *const[], char *const[]);

static execve_fn real_execve(void) {
    static execve_fn cached = NULL;
    if (cached == NULL) {
        cached = (execve_fn) dlsym(RTLD_NEXT, "execve");
    }
    return cached;
}

/*
 * /proc/self/exe interception.
 *
 * Under linker-exec the kernel's idea of the running program is the linker, so
 * readlink("/proc/self/exe") answers "/system/bin/linker64" (or the APEX path) instead of the
 * program's own path. Anything that locates its installation that way is then wrong about where
 * it lives.
 *
 * Node is the case that proved it: `process.execPath` reported
 * `/apex/com.android.runtime/bin/linker64`, which is where npm and module resolution would have
 * looked. This file already set TERMUX_EXEC__PROC_SELF_EXE for the child — but nothing read it,
 * so the "shim" was a variable no one honoured. The interception below is what makes it real.
 */
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);

static readlink_fn real_readlink(void) {
    static readlink_fn cached = NULL;
    if (cached == NULL) cached = (readlink_fn) dlsym(RTLD_NEXT, "readlink");
    return cached;
}

static readlinkat_fn real_readlinkat(void) {
    static readlinkat_fn cached = NULL;
    if (cached == NULL) cached = (readlinkat_fn) dlsym(RTLD_NEXT, "readlinkat");
    return cached;
}

/* True for "/proc/self/exe" and for this process's own "/proc/<pid>/exe". */
static int is_own_exe_path(const char *path) {
    if (path == NULL) return 0;
    if (strcmp(path, "/proc/self/exe") == 0) return 1;

    char own[64];
    const int written = snprintf(own, sizeof(own), "/proc/%d/exe", getpid());
    return written > 0 && (size_t) written < sizeof(own) && strcmp(path, own) == 0;
}

/*
 * Answers with the real program path when one was recorded.
 *
 * Returns the byte count on success, or -1 when this is not a request we should answer, leaving
 * errno untouched so the caller can fall through to the real readlink.
 */
static ssize_t shimmed_exe_path(const char *path, char *buffer, size_t size) {
    if (!is_own_exe_path(path)) return -1;

    const char *actual = getenv("TERMUX_EXEC__PROC_SELF_EXE");
    if (actual == NULL || actual[0] == '\0') return -1;

    /* readlink does not NUL-terminate, and truncates rather than failing. */
    const size_t length = strlen(actual);
    const size_t copied = (length < size) ? length : size;
    memcpy(buffer, actual, copied);
    return (ssize_t) copied;
}

MF_EXPORT ssize_t readlink(const char *path, char *buffer, size_t size) {
    const ssize_t shimmed = shimmed_exe_path(path, buffer, size);
    if (shimmed >= 0) return shimmed;
    return real_readlink()(path, buffer, size);
}

MF_EXPORT ssize_t readlinkat(int fd, const char *path, char *buffer, size_t size) {
    /* Only an absolute path can be /proc/self/exe; a relative one is resolved against fd. */
    if (path != NULL && path[0] == '/') {
        const ssize_t shimmed = shimmed_exe_path(path, buffer, size);
        if (shimmed >= 0) return shimmed;
    }
    return real_readlinkat()(fd, path, buffer, size);
}

/* The prefix this app installs into, supplied by RuntimeEnvironmentBuilder. */
static const char *mf_prefix(void) {
    const char *prefix = getenv("MOBILEFORGE_PREFIX");
    return (prefix != NULL && prefix[0] != '\0') ? prefix : NULL;
}

static int starts_with(const char *text, const char *prefix) {
    const size_t length = strlen(prefix);
    return strncmp(text, prefix, length) == 0 &&
           (text[length] == '/' || text[length] == '\0');
}

/*
 * Rewrites "/data/user/<id>/X" to "/data/data/X".
 *
 * Android exposes app storage under both spellings and they are the same directory, but they
 * arrive here from different places: MOBILEFORGE_PREFIX comes from Context.filesDir, which
 * reports /data/user/0/..., while a package's own paths are baked in at build time as
 * /data/data/.... git is compiled with its exec-path in the second form, so a plain string
 * comparison decided git's own helpers were outside the prefix, skipped the rewrite, and left
 * the kernel to refuse them under W^X:
 *
 *     fatal: cannot exec 'maintenance': Permission denied
 *
 * Done as string normalisation rather than realpath() because this runs inside execve(), where
 * allocation is best avoided.
 *
 * Returns `out` when it rewrote, otherwise `path` unchanged.
 */
static const char *normalise_app_path(const char *path, char *out, size_t size) {
    static const char user_root[] = "/data/user/";
    static const char data_root[] = "/data/data";
    const size_t user_length = sizeof(user_root) - 1;
    const size_t data_length = sizeof(data_root) - 1;

    if (path == NULL || strncmp(path, user_root, user_length) != 0) return path;

    const char *digits = path + user_length;
    const char *cursor = digits;
    while (*cursor >= '0' && *cursor <= '9') cursor++;

    /* Must be /data/user/<digits>/ — "/data/userdata/..." must not be rewritten. */
    if (cursor == digits || *cursor != '/') return path;

    if (data_length + strlen(cursor) + 1 > size) return path;
    memcpy(out, data_root, data_length);
    strcpy(out + data_length, cursor);
    return out;
}

/* True when `path` is inside `prefix`, under either spelling of the app data directory. */
static int path_under_prefix(const char *path, const char *prefix) {
    if (starts_with(path, prefix)) return 1;

    char path_buffer[PATH_MAX];
    char prefix_buffer[PATH_MAX];
    const char *normalised_path = normalise_app_path(path, path_buffer, sizeof(path_buffer));
    const char *normalised_prefix =
        normalise_app_path(prefix, prefix_buffer, sizeof(prefix_buffer));

    return starts_with(normalised_path, normalised_prefix);
}

/*
 * Reads the first bytes of a file.
 *
 * Uses open/read directly rather than stdio: this runs inside an execve() call, possibly
 * between fork() and exec in a threaded process, where only async-signal-safe work is legal.
 */
static ssize_t read_header(const char *path, char *buffer, size_t size) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }
    const ssize_t got = read(fd, buffer, size);
    close(fd);
    return got;
}

static int is_elf(const char *header, ssize_t length) {
    return length >= 4 && header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' &&
           header[3] == 'F';
}

/* ET_DYN (3) is what the linker can load; ET_EXEC (2) static binaries it cannot. */
static int is_dynamic_elf(const char *header, ssize_t length) {
    if (!is_elf(header, length) || length < 18) {
        return 0;
    }
    const int e_type = (unsigned char) header[16] | ((unsigned char) header[17] << 8);
    return e_type == 3;
}

/*
 * Extracts a shebang interpreter and its single optional argument.
 *
 * Matches the kernel: truncates at 127 bytes and does not split past the first space, so a
 * script behaves the same here as it would in any other shell.
 */
static int parse_shebang(const char *header, ssize_t length, char *interpreter, char *argument) {
    if (length < 3 || header[0] != '#' || header[1] != '!') {
        return 0;
    }

    ssize_t end = 2;
    while (end < length && end < SHEBANG_MAX && header[end] != '\n' && header[end] != '\r') {
        end++;
    }

    ssize_t start = 2;
    while (start < end && (header[start] == ' ' || header[start] == '\t')) {
        start++;
    }
    if (start >= end) {
        return 0;
    }

    ssize_t split = start;
    while (split < end && header[split] != ' ' && header[split] != '\t') {
        split++;
    }

    const size_t interpreter_length = (size_t) (split - start);
    if (interpreter_length == 0 || interpreter_length >= PATH_MAX) {
        return 0;
    }
    memcpy(interpreter, header + start, interpreter_length);
    interpreter[interpreter_length] = '\0';

    argument[0] = '\0';
    ssize_t arg_start = split;
    while (arg_start < end && (header[arg_start] == ' ' || header[arg_start] == '\t')) {
        arg_start++;
    }
    if (arg_start < end) {
        size_t arg_length = (size_t) (end - arg_start);
        if (arg_length >= PATH_MAX) {
            arg_length = PATH_MAX - 1;
        }
        memcpy(argument, header + arg_start, arg_length);
        argument[arg_length] = '\0';
    }
    return 1;
}

MF_EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    const execve_fn original = real_execve();
    if (original == NULL) {
        errno = ENOSYS;
        return -1;
    }

    const char *prefix = mf_prefix();

    /* Not our business: no prefix configured, or the program lives outside it. */
    if (path == NULL || prefix == NULL || !path_under_prefix(path, prefix)) {
        return original(path, argv, envp);
    }

    /* Never rewrite the linker itself — that would recurse forever. */
    if (strcmp(path, SYSTEM_LINKER) == 0) {
        return original(path, argv, envp);
    }

    char header[HEADER_BYTES];
    const ssize_t header_length = read_header(path, header, sizeof(header));
    if (header_length <= 0) {
        return original(path, argv, envp);
    }

    char interpreter[PATH_MAX];
    char shebang_argument[PATH_MAX];
    const int has_shebang = parse_shebang(header, header_length, interpreter, shebang_argument);

    /*
     * A static ELF cannot be loaded by the linker. Pass it through so the caller sees the real
     * "Permission denied" rather than a misleading linker failure.
     */
    if (!has_shebang && is_elf(header, header_length) &&
        !is_dynamic_elf(header, header_length)) {
        return original(path, argv, envp);
    }

    size_t argc = 0;
    while (argv != NULL && argc < MAX_ARGS && argv[argc] != NULL) {
        argc++;
    }
    if (argc == MAX_ARGS) { errno = E2BIG; return -1; }

    /* linker + [interpreter] + [shebang arg] + script/program + original args + NULL */
    char *rewritten[MAX_ARGS + 5];
    size_t out = 0;

    rewritten[out++] = (char *) SYSTEM_LINKER;

    if (has_shebang) {
        /*
         * The kernel would have run the interpreter; since it is being handed the linker
         * instead, we do that job here.
         */
        rewritten[out++] = interpreter;
        if (shebang_argument[0] != '\0') {
            rewritten[out++] = shebang_argument;
        }
        rewritten[out++] = (char *) path;
        /* argv[0] of a script is the script name, which we have already added. */
        for (size_t i = 1; i < argc; i++) {
            rewritten[out++] = argv[i];
        }
    } else {
        rewritten[out++] = (char *) path;
        for (size_t i = 1; i < argc; i++) {
            rewritten[out++] = argv[i];
        }
    }

    rewritten[out] = NULL;

    /* Build a child-only environment: setenv here could deadlock after fork and
     * would incorrectly change the parent if exec fails. Replace every stale shim. */
    const char shim_key[] = "TERMUX_EXEC__PROC_SELF_EXE=";
    const char *executable = has_shebang ? interpreter : path;
    char shim[sizeof(shim_key) + PATH_MAX];
    const size_t path_length = strlen(executable);
    if (path_length >= PATH_MAX) { errno = ENAMETOOLONG; return -1; }
    memcpy(shim, shim_key, sizeof(shim_key) - 1);
    memcpy(shim + sizeof(shim_key) - 1, executable, path_length + 1);
    char *child_env[MAX_ARGS + 2];
    size_t env_count = 0;
    size_t kept = 0;
    while (envp != NULL && env_count < MAX_ARGS && envp[env_count] != NULL) {
        if (strncmp(envp[env_count], shim_key, sizeof(shim_key) - 1) != 0) {
            child_env[kept++] = envp[env_count];
        }
        env_count++;
    }
    if (env_count == MAX_ARGS) { errno = E2BIG; return -1; }
    child_env[kept++] = shim;
    child_env[kept] = NULL;
    return original(SYSTEM_LINKER, rewritten, child_env);
}

MF_EXPORT int execv(const char *path, char *const argv[]) {
    extern char **environ;
    return execve(path, argv, environ);
}

/*
 * execvp and execvpe MUST be overridden separately.
 *
 * An earlier version of this file assumed that overriding execve was enough, on the grounds
 * that bionic implements the exec family in terms of it. That is true of the implementation and
 * false of the interposition: libc's internal call to execve is resolved inside libc.so and
 * never goes through the PLT, so LD_PRELOAD does not see it.
 *
 * The symptom on a device was git failing to run its own subcommands:
 *
 *     fatal: cannot exec 'maintenance': Permission denied
 *
 * git uses execvp, the search hit $PREFIX/bin/git-maintenance, and the kernel refused it under
 * W^X because our rewrite never ran. PATH resolution is therefore done here, so each candidate
 * goes through our own execve above.
 */
static int mf_execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (file == NULL || *file == '\0') {
        errno = ENOENT;
        return -1;
    }

    /* A path with a slash is used as-is, exactly as execvp specifies. */
    if (strchr(file, '/') != NULL) {
        return execve(file, argv, envp);
    }

    const char *path = NULL;
    for (char *const *entry = envp; entry != NULL && *entry != NULL; entry++) {
        if (strncmp(*entry, "PATH=", 5) == 0) {
            path = *entry + 5;
            break;
        }
    }
    if (path == NULL) path = getenv("PATH");
    if (path == NULL) path = "/system/bin:/system/xbin";

    /*
     * Report the most informative failure, matching execvp: EACCES beats ENOENT, because
     * "found but not runnable" tells the user far more than "not found".
     */
    int saved_errno = ENOENT;
    const char *segment = path;

    while (*segment != '\0') {
        const char *end = strchr(segment, ':');
        size_t length = (end != NULL) ? (size_t) (end - segment) : strlen(segment);

        char candidate[PATH_MAX];
        /* An empty PATH segment means the current directory. */
        const char *dir = (length == 0) ? "." : segment;
        size_t dir_length = (length == 0) ? 1 : length;

        if (dir_length + 1 + strlen(file) + 1 <= sizeof(candidate)) {
            memcpy(candidate, dir, dir_length);
            candidate[dir_length] = '/';
            strcpy(candidate + dir_length + 1, file);

            execve(candidate, argv, envp);
            if (errno == EACCES) saved_errno = EACCES;
        }

        if (end == NULL) break;
        segment = end + 1;
    }

    errno = saved_errno;
    return -1;
}

MF_EXPORT int execvp(const char *file, char *const argv[]) {
    extern char **environ;
    return mf_execvpe(file, argv, environ);
}

MF_EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) {
    return mf_execvpe(file, argv, envp);
}
