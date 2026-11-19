package app.dpadmouse.service

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.dpadmouse.DLog
import app.dpadmouse.MouseHub
import app.dpadmouse.shell.ShellBackend
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Blocks keys with EVIOCGRAB through the native helper (native/evgrab.c) running as shell.
 * The helper keeps the grab for the whole session and re-injects every key it does not consume,
 * so only the mouse keys are blocked and the accessibility service is not needed.
 */
class EvGrab(
    private val context: Context,
    private val shell: ShellBackend,
    private val io: Executor,
    private val onLost: () -> Unit
) {
    class NoRemote : IOException("no remote selected")

    private val main = Handler(Looper.getMainLooper())
    private val replies = LinkedBlockingQueue<String>()
    private val extra = StringBuffer()
    @Volatile private var scanToKey: Map<Int, Int> = emptyMap()
    private var sentKeys: List<Int>? = null
    private var sentProtect: List<Int>? = null
    private var sentDevices: List<String>? = null

    /** Keeps the helper's fail-open watchdog fed; a dead helper is noticed here. */
    private val beat = object : Runnable {
        override fun run() {
            io.execute {
                runCatching { shell.writeEvGrab("P") }.onFailure {
                    main.post { onLost() }
                }
            }
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** Blocking; call from the io thread. */
    fun start() {
        // Only the remote the user mapped keys on is ever grabbed.
        if (MouseHub.prefs.grabDevices().isEmpty()) throw NoRemote()
        val remote = install()
        shell.startEvGrab("exec $remote") { onLine(it) }
        expect("READY")
        pushConfig()
        shell.writeEvGrab("G 1")
        expect("OK", 6)
        main.postDelayed(beat, HEARTBEAT_MS)
        DLog.i("EVIOCGRAB active")
    }

    fun stop() {
        main.removeCallbacks(beat)
        runCatching { shell.stopEvGrab() }
    }

    /** Re-sends the consumed key list after the mode or the key mapping changed. */
    fun refresh() {
        io.execute { runCatching { pushConfig() }.onFailure { DLog.w("evgrab refresh: ${it.message}") } }
    }

    @Synchronized
    private fun pushConfig() {
        val prefs = MouseHub.prefs
        val plan = ScanMap.build(prefs, MouseHub.mouseModeOn, MouseHub.learningKeys)
        scanToKey = plan.map
        val devices = prefs.grabDevices().sorted()
        if (devices != sentDevices) {
            shell.writeEvGrab("DC")
            devices.forEach { shell.writeEvGrab("D $it") }
            sentDevices = devices
        }
        if (plan.protect != sentProtect) {
            shell.writeEvGrab("W " + plan.protect.joinToString(" "))
            sentProtect = plan.protect
        }
        if (plan.consume != sentKeys) {
            shell.writeEvGrab("K " + plan.consume.joinToString(" "))
            sentKeys = plan.consume
        }
    }

    private fun onLine(line: String) {
        when {
            line.startsWith("E ") -> {
                val p = line.split(' ')
                val scan = p.getOrNull(1)?.toIntOrNull() ?: return
                val value = p.getOrNull(2)?.toIntOrNull() ?: return
                val key = scanToKey[scan] ?: return
                val down = value == 1
                // Same thread the accessibility service used; also keeps writes off the reader thread.
                main.post { KeyRouter.handle(MouseHub.prefs, key, down, down) }
            }
            line.startsWith("READY") || line == "OK" || line.startsWith("ERR") -> replies.offer(line)
            line == "REGRAB" -> DLog.i("evgrab: grab re-armed")
            else -> {
                DLog.w("evgrab: $line")
                if (extra.length < 400) extra.append(line).append(' ')
            }
        }
    }

    private fun expect(prefix: String, seconds: Long = 4) {
        val line = replies.poll(seconds, TimeUnit.SECONDS)
            ?: throw IOException("evgrab did not respond ${extra.toString().trim()}".trim())
        if (line.startsWith("ERR")) throw IOException(line.removePrefix("ERR").trim())
        if (!line.startsWith(prefix)) throw IOException("evgrab: unexpected '$line'")
    }

    /** Copies the helper for this device's ABI to /data/local/tmp (once per build) and returns its path. */
    private fun install(): String {
        val have = context.assets.list("evgrab")?.toSet().orEmpty()
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in have }
            ?: throw IOException("evgrab helper missing (run tools/build-evgrab.sh)")
        val bytes = context.assets.open("evgrab/$abi").use { it.readBytes() }
        val hash = MessageDigest.getInstance("MD5").digest(bytes).take(4).joinToString("") { "%02x".format(it) }
        val remote = "/data/local/tmp/dpadmouse-evgrab-$hash"
        if (!shell.exec("[ -x $remote ] && echo Y").contains("Y")) {
            val dir = context.getExternalFilesDir(null) ?: throw IOException("No shared storage to stage the helper")
            val src = File(dir, "evgrab.bin")
            try {
                src.writeBytes(bytes)
                val out = shell.exec("rm -f /data/local/tmp/dpadmouse-evgrab-*; cp '${src.path}' $remote && chmod 755 $remote && echo Y")
                if (!out.contains("Y")) throw IOException("Helper install failed: ${out.trim()}")
            } finally {
                src.delete()
            }
        }
        return remote
    }

    private companion object {
        const val HEARTBEAT_MS = 3_000L
    }
}
