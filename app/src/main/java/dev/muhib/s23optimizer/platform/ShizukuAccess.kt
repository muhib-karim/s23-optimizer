package dev.muhib.s23optimizer.platform

import android.content.pm.PackageManager
import android.os.Process
import dev.muhib.s23optimizer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.InputStream

data class ShellAccess(val running: Boolean = false, val granted: Boolean = false, val shellIdentity: Boolean = false, val message: String = "Shizuku is not running.") {
    val ready: Boolean get() = running && granted && shellIdentity
}

class ShizukuAccess {
    private val mutable = MutableStateFlow(ShellAccess())
    val state = mutable.asStateFlow()
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { mutable.value = ShellAccess(message = "Shizuku disconnected. Start it again after reboot.") }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }
    fun refresh() {
        mutable.value = runCatching {
            val running = Shizuku.pingBinder()
            val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val shell = granted && Shizuku.getUid() == 2000 && Process.myUid() / 100_000 == 0
            ShellAccess(running, granted, shell, when {
                !running -> "Shizuku is not running. Pair and start it on this phone."
                !granted -> "Shizuku is running; permission is needed."
                !shell -> "Use Shizuku in wireless-debugging shell mode on the owner profile. Root and secondary profiles are not supported."
                else -> "Shizuku connected • shell permission granted"
            })
        }.getOrElse { ShellAccess(message = "Shizuku unavailable: ${it.message}") }
    }
    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(23) }
            .onFailure { mutable.value = ShellAccess(message = "Permission request failed: ${it.message}") }
        refresh()
    }
}

class ShizukuShell(private val access: ShizukuAccess) : ShellRunner {
    companion object { private const val MAX_OUTPUT = 4 * 1024 * 1024 }
    private fun readBounded(stream: InputStream): String {
        return stream.bufferedReader().use { reader ->
            val out = StringBuilder(); val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                check(out.length + count <= MAX_OUTPUT) { "Shell output exceeded safety limit." }
                out.append(buffer, 0, count)
            }
            out.toString()
        }
    }
    @Suppress("DEPRECATION")
    override suspend fun run(command: ShellCommand): ShellResult = withContext(Dispatchers.IO) {
        access.refresh()
        if (!access.state.value.ready) return@withContext ShellResult(-1, "", access.state.value.message)
        var process: java.lang.Process? = null
        try {
            // Direct argv; no interpolating shell, pipelines or arbitrary user commands.
            // Shizuku 13 made newProcess private; call it reflectively (same argv, no shell).
            process = shizukuNewProcess(command.args.toTypedArray())
            val child = requireNotNull(process)
            child.outputStream.close()
            withTimeout(12_000) {
                coroutineScope {
                    val out = async { runInterruptible { readBounded(child.inputStream) } }
                    val err = async { runInterruptible { readBounded(child.errorStream) } }
                    try {
                        var code: Int? = null
                        while (code == null) {
                            code = try { child.exitValue() } catch (_: IllegalThreadStateException) { null }
                            if (code == null) delay(40)
                        }
                        ShellResult(code, out.await(), err.await())
                    } finally {
                        // Closing pipes on cancellation releases blocked reader threads.
                        runCatching { child.destroy() }
                        runCatching { child.inputStream.close() }
                        runCatching { child.errorStream.close() }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            ShellResult(-1, "", "Shell command timed out after 12 seconds; state may be uncertain.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ShellResult(-1, "", "Shell unavailable: ${e.message}") }
        finally { runCatching { process?.destroy() } }
    }
}

private val newProcessMethod by lazy {
    Shizuku::class.java.getDeclaredMethod(
        "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
    ).apply { isAccessible = true }
}

private fun shizukuNewProcess(argv: Array<String>): java.lang.Process =
    newProcessMethod.invoke(null, argv, null, null) as java.lang.Process
