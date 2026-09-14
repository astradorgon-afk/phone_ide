/*
 * Native pseudo-terminal support.
 *
 * WHY THIS EXISTS
 *
 * `ProcessBuilder` (used by :runtime:exec) gives pipes. Pipes are correct for running a build
 * or a server, and completely wrong for an interactive terminal:
 *
 *   - `isatty()` returns false, so shells disable job control, colour and prompts, and tools
 *     like composer, npm and git switch to their non-interactive output modes.
 *   - There is no line discipline: no echo, no line editing, no canonical mode.
 *   - There is no controlling terminal, so Ctrl+C cannot deliver SIGINT to a process group.
 *   - There is no window size, so anything using ncurses or readline misbehaves.
 *
 * None of that can be emulated from Java. A real terminal needs a real PTY, which needs
 * `forkpty()` — hence this small C layer. bionic has had `openpty`/`forkpty`/`login_tty` since
 * API 23; our minSdk is 30, so they are always available.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO
 *
 * It does not decide *what* to run, does not build argv, and knows nothing about the system
 * linker. The caller passes a fully-resolved argv from ExecCommandBuilder (ADR-009), so the
 * Android exec workaround stays in exactly one place.
 */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/*
 * Distinct exit codes for the two ways the child can fail before it ever runs the program.
 *
 * They are deliberately in the 120s, clear of the 0-125 range a real program might return and
 * clear of the 128+signal range. Kotlin maps them back to specific messages, so "the working
 * directory disappeared" is never reported as "the shell exited with an error".
 */
#define EXIT_CHDIR_FAILED 126
#define EXIT_EXEC_FAILED 127

/* Frees a NULL-terminated char** built by to_c_array(). */
static void free_c_array(char **array) {
    if (array == NULL) {
        return;
    }
    for (size_t i = 0; array[i] != NULL; i++) {
        free(array[i]);
    }
    free(array);
}

/*
 * Converts a Java String[] into a NULL-terminated char**.
 *
 * Returns NULL on allocation failure, having freed anything partially built. The caller must
 * free the result with free_c_array().
 */
static char **to_c_array(JNIEnv *env, jobjectArray array) {
    const jsize count = (*env)->GetArrayLength(env, array);
    char **result = calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL) {
        return NULL;
    }

    for (jsize i = 0; i < count; i++) {
        jstring element = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, element, NULL);
        if (chars == NULL) {
            free_c_array(result);
            (*env)->DeleteLocalRef(env, element);
            return NULL;
        }
        result[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, element, chars);
        (*env)->DeleteLocalRef(env, element);

        if (result[i] == NULL) {
            free_c_array(result);
            return NULL;
        }
    }
    return result;
}

static void throw_io_exception(JNIEnv *env, const char *message) {
    jclass clazz = (*env)->FindClass(env, "java/io/IOException");
    if (clazz != NULL) {
        (*env)->ThrowNew(env, clazz, message);
    }
}

/*
 * Creates a PTY and forks a child attached to it.
 *
 * Returns the master fd; writes the child pid into pidOut[0].
 */
JNIEXPORT jint JNICALL
Java_dev_mobileforge_runtime_pty_NativePty_nativeForkPty(
        JNIEnv *env,
        jclass clazz,
        jobjectArray argvArray,
        jstring cwdString,
        jobjectArray envArray,
        jint rows,
        jint cols,
        jintArray pidOut) {
    (void) clazz;

    char **argv = to_c_array(env, argvArray);
    char **envp = to_c_array(env, envArray);
    const char *cwd = (*env)->GetStringUTFChars(env, cwdString, NULL);

    if (argv == NULL || envp == NULL || cwd == NULL || argv[0] == NULL) {
        free_c_array(argv);
        free_c_array(envp);
        if (cwd != NULL) {
            (*env)->ReleaseStringUTFChars(env, cwdString, cwd);
        }
        throw_io_exception(env, "Invalid arguments for forkpty");
        return -1;
    }

    /*
     * Terminal modes for the child.
     *
     * These are what make it feel like a terminal rather than a pipe: canonical line editing,
     * echo, signal generation from Ctrl+C / Ctrl+Z, and CRNL translation so that a bare "\n"
     * from a program lands as a proper new line.
     */
    struct termios term;
    memset(&term, 0, sizeof(term));
    term.c_iflag = ICRNL | IXON | IUTF8;
    term.c_oflag = OPOST | ONLCR;
    term.c_cflag = CREAD | CS8 | HUPCL;
    term.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;

    term.c_cc[VINTR] = 3;    /* Ctrl+C  -> SIGINT  */
    term.c_cc[VQUIT] = 28;   /* Ctrl+\  -> SIGQUIT */
    term.c_cc[VERASE] = 127; /* Backspace          */
    term.c_cc[VKILL] = 21;   /* Ctrl+U             */
    term.c_cc[VEOF] = 4;     /* Ctrl+D  -> EOF     */
    term.c_cc[VSUSP] = 26;   /* Ctrl+Z  -> SIGTSTP */
    term.c_cc[VMIN] = 1;
    term.c_cc[VTIME] = 0;

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    size.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master_fd = -1;
    const pid_t pid = forkpty(&master_fd, NULL, &term, &size);

    if (pid < 0) {
        const int saved = errno;
        free_c_array(argv);
        free_c_array(envp);
        (*env)->ReleaseStringUTFChars(env, cwdString, cwd);
        throw_io_exception(env, strerror(saved));
        return -1;
    }

    if (pid == 0) {
        /*
         * Child. Nothing here may allocate through the JVM or touch JNI — after fork() only
         * async-signal-safe work is legal, and the JVM's locks may be held by threads that no
         * longer exist in this process.
         */
        if (chdir(cwd) != 0) {
            _exit(EXIT_CHDIR_FAILED);
        }

        /*
         * Reset signal handling. The JVM installs handlers for its own purposes; a child that
         * inherits them will not respond to Ctrl+C the way a shell must.
         */
        sigset_t signals;
        sigfillset(&signals);
        sigprocmask(SIG_UNBLOCK, &signals, NULL);
        for (int signum = 1; signum < NSIG; signum++) {
            signal(signum, SIG_DFL);
        }

        execve(argv[0], argv, envp);

        /* execve only returns on failure. */
        _exit(EXIT_EXEC_FAILED);
    }

    /* Parent. */
    free_c_array(argv);
    free_c_array(envp);
    (*env)->ReleaseStringUTFChars(env, cwdString, cwd);

    jint pid_value = (jint) pid;
    (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &pid_value);

    return (jint) master_fd;
}

JNIEXPORT void JNICALL
Java_dev_mobileforge_runtime_pty_NativePty_nativeResize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) clazz;

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &size);
}

/*
 * Sends a signal to the child's PROCESS GROUP, not just the child.
 *
 * This is the difference between Ctrl+C working and not: a shell running `sleep 100` puts the
 * sleep in a foreground process group, and signalling only the shell's pid leaves the sleep
 * running. Negating the pid targets the group.
 */
JNIEXPORT jint JNICALL
Java_dev_mobileforge_runtime_pty_NativePty_nativeSignalGroup(
        JNIEnv *env, jclass clazz, jint pid, jint signal_number) {
    (void) env;
    (void) clazz;
    return (jint) killpg((pid_t) pid, (int) signal_number);
}

/*
 * Reaps the child.
 *
 * blocking=false is used for polling from Kotlin without tying up a thread per process.
 * Returns the exit status, or -1 when the child is still alive.
 */
JNIEXPORT jint JNICALL
Java_dev_mobileforge_runtime_pty_NativePty_nativeWaitFor(
        JNIEnv *env, jclass clazz, jint pid, jboolean blocking) {
    (void) env;
    (void) clazz;

    int status = 0;
    const pid_t result = waitpid((pid_t) pid, &status, blocking ? 0 : WNOHANG);

    if (result == 0) {
        return -1; /* still running */
    }
    if (result < 0) {
        return -2; /* already reaped, or not our child */
    }

    if (WIFEXITED(status)) {
        return (jint) WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        /* Shell convention: 128 + signal. Lets Kotlin recognise SIGKILL as 137. */
        return (jint) (128 + WTERMSIG(status));
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_dev_mobileforge_runtime_pty_NativePty_nativeClose(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd >= 0) {
        close(fd);
    }
}
