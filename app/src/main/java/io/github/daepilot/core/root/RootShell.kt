package io.github.daepilot.core.root

import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class ShellResult(
    val code: Int,
    val out: String,
    val err: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = !timedOut && code == 0
}

enum class RootRequestResult {
    GRANTED,
    DENIED,
    TIMEOUT,
    ERROR,
}

/**
 * A single libsu root session. Commands are always passed as one shell string,
 * so callers must use [quote] for user-controlled arguments.
 */
class RootShell {
    // The UI and the foreground service can both discover a missing session after
    // a reboot. Serialise acquisition so only one su request is in flight.
    private val requestMutex = Mutex()

    /**
     * `Shell.isAppGrantedRoot()` only reports true when the app process itself
     * runs as uid 0, so it cannot be used to detect a granted su session. The
     * cached shell is the authoritative source once a request has happened.
     */
    suspend fun hasRootShell(): Boolean = withContext(Dispatchers.IO) {
        val shell = runCatching { Shell.getCachedShell() }.getOrNull() ?: return@withContext false
        runCatching { shell.isAlive && shell.isRoot }.getOrDefault(false)
    }

    suspend fun isRootAvailable(): Boolean = hasRootShell()

    /**
     * Starts (or refreshes) the su session in a way that makes the root manager
     * show its authorization dialog. A previously cached non-root shell is
     * closed first so a remembered denial can be retried without restarting.
     */
    suspend fun requestRoot(timeoutMs: Long = 90_000): RootRequestResult =
        requestMutex.withLock {
            withContext(Dispatchers.IO) {
                if (hasRootShell()) return@withContext RootRequestResult.GRANTED
                runCatching {
                    Shell.getCachedShell()?.takeIf { !it.isRoot && it.isAlive }?.close()
                }
                // Separate scope: Shell.getShell() blocks until the su dialog is
                // answered, and a child coroutine would keep the caller waiting
                // even after withTimeout has already given up on it.
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val deferred = scope.async {
                    runCatching {
                        // After closing a cached non-root shell libsu may hand the
                        // closed instance back once; a second call builds afresh
                        // and triggers the authorization dialog again.
                        var shell = Shell.getShell()
                        if (!shell.isAlive) shell = Shell.getShell()
                        shell
                    }
                }
                try {
                    val shell = withTimeout(timeoutMs) { deferred.await() }.getOrElse { error ->
                        return@withContext if (error is NoShellException) {
                            RootRequestResult.DENIED
                        } else {
                            RootRequestResult.ERROR
                        }
                    }
                    if (shell.isAlive && shell.isRoot) {
                        RootRequestResult.GRANTED
                    } else {
                        RootRequestResult.DENIED
                    }
                } catch (e: TimeoutCancellationException) {
                    RootRequestResult.TIMEOUT
                } catch (e: Exception) {
                    RootRequestResult.ERROR
                } finally {
                    scope.cancel()
                }
            }
        }

    suspend fun run(command: String, timeoutMs: Long = 20_000): ShellResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val job = Shell.cmd(command)
                val deferred = async(Dispatchers.IO) { job.exec() }
                try {
                    val result = withTimeout(timeoutMs) { deferred.await() }
                    ShellResult(
                        code = result.code,
                        out = result.out.joinToString("\n"),
                        err = result.err.joinToString("\n"),
                    )
                } catch (e: TimeoutCancellationException) {
                    // libsu 5.2.2 has no Job.cancel(); the shell command is
                    // bounded by the per-shell timeout configured on startup.
                    ShellResult(
                        code = -1,
                        out = "",
                        err = "命令超时：${e.message ?: "timeout"}",
                        timedOut = true,
                    )
                }
            }.getOrElse { e ->
                ShellResult(-1, "", "无法执行 root 命令：${e.message ?: e.javaClass.simpleName}")
            }
        }

    /** Runs a command and returns stdout even on nonzero exit (e.g. `grep`). */
    suspend fun runLenient(command: String, timeoutMs: Long = 20_000): ShellResult =
        run("($command) 2>/dev/null || true", timeoutMs)

    suspend fun writeFile(path: String, content: String, mode: String = "600"): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = SuFile(path)
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                SuFileOutputStream.open(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                if (mode.isNotBlank()) {
                    Shell.cmd("chmod $mode ${quote(path)}").exec()
                }
                true
            }.getOrDefault(false)
        }

    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        val result = run("cat ${quote(path)}")
        if (result.ok) result.out else null
    }

    suspend fun exists(path: String): Boolean =
        run("test -e ${quote(path)} && echo yes").out.trim() == "yes"

    companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun initialize() {
            runCatching {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(15),
                )
            }
        }

        /** Only allow simple package names / shell words in rendered config. */
        fun isSafeToken(value: String): Boolean =
            value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "._-:" }
    }
}
