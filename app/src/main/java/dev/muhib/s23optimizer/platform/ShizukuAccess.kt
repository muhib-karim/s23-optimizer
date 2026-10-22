package dev.muhib.s23optimizer.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import dev.muhib.s23optimizer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.InputStream

/** Reads Shizuku's binder state; every call may throw if the binder dies mid-read. */
class BinderProbe(private val context: Context) : ShizukuProbe {
    override fun installed() = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)
    override fun running() = Shizuku.pingBinder()
    override fun granted() = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    override fun deniedPermanently() = Shizuku.shouldShowRequestPermissionRationale()
    override fun serverUid() = Shizuku.getUid()
    override fun ownerProfile() = Process.myUid() / 100_000 == 0
    companion object { const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api" }
}

class ShizukuAccess(private val probe: ShizukuProbe) {
    private val mutable = MutableStateFlow(ShellAccess())
    val state = mutable.asStateFlow()
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener {
        mutable.value = ShellAccess(AccessState.NOT_RUNNING, "Shizuku disconnected. Start it again in Shizuku (needed after every reboot).", AccessAction.OPEN_SHIZUKU)
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }
    fun refresh() { mutable.value = AccessResolver.resolve(probe) }
    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(23) }
            .onFailure { mutable.value = ShellAccess(AccessState.NOT_RUNNING, "Permission request failed: ${it.message}", AccessAction.OPEN_SHIZUKU) }
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
