package app.dpadmouse

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import app.dpadmouse.hid.HidMouse
import app.dpadmouse.service.CursorEngine
import app.dpadmouse.service.MouseAccessibilityService
import app.dpadmouse.shell.AdbBackend
import app.dpadmouse.shell.ShellBackend
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Shared state hub used by the Activity and the AccessibilityService. */
object MouseHub {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    lateinit var appContext: Context
        private set
    lateinit var prefs: Prefs
        private set

    private val main = Handler(Looper.getMainLooper())

    /** Worker threads exist only while there is work (core size 0, 10 s keep-alive) -> no idle threads. */
    private fun idleExecutor(name: String): ExecutorService =
        ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, name).apply { isDaemon = true }
        }

    /** Connection lifecycle + one-off shell commands. */
    private val io = idleExecutor("dpadmouse-io")

    /** Enabling the accessibility service can block for seconds; kept off [io] so it never delays connect/disconnect. */
    private val a11yExec = idleExecutor("dpadmouse-a11y")

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var mouseModeOn = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var engine: CursorEngine? = null
        private set
    @Volatile var serviceConnected = false
        private set

    /** In the key-mapping dialog: the accessibility service must release every key so the dialog can receive it. */
    @Volatile var learningKeys = false

    @Volatile private var backend: ShellBackend? = null
    @Volatile private var enableAfterConnect = false

    // --- accessibility enabling (single in-flight run, extra callers just wait for its result)
    private val a11yWaiters = ArrayList<(Result<String>) -> Unit>()
    @Volatile private var a11yBusy = false
    private val svcLock = ReentrantLock()
    private val svcCond = svcLock.newCondition()

    // --- auto-recovery when the system drops our accessibility entry while mouse mode is ON
    private var watching = false
    private var recoverWindowStart = 0L
    private var recoverCount = 0
    private val a11yObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(recoverRunnable)
            main.postDelayed(recoverRunnable, RECOVER_DELAY_MS)
        }
    }
    private val recoverRunnable = Runnable {
        if (!mouseModeOn || serviceConnected || a11yBusy) return@Runnable
        // Our entry is still listed -> the system is (re)binding it, give it time.
        if (isListed()) return@Runnable
        val now = SystemClock.uptimeMillis()
        if (now - recoverWindowStart > RECOVER_WINDOW_MS) {
            recoverWindowStart = now
            recoverCount = 0
        }
        // Rate limit so we never fight a user who disables the service on purpose.
        if (recoverCount >= RECOVER_MAX) {
            DLog.w("Accessibility keeps being disabled; giving up auto-recovery")
            return@Runnable
        }
        recoverCount++
        DLog.i("Accessibility service was disabled - re-enabling")
        enableAccessibilityService { r -> r.onFailure { DLog.w("Auto-recover failed: ${it.message}") } }
    }

    // --- release shell resources (adb socket + the `hid` JVM on the device) when mouse mode stays OFF
    private val idleRunnable = Runnable {
        if (mouseModeOn || state != State.CONNECTED) return@Runnable
        io.execute {
            if (!mouseModeOn && state == State.CONNECTED) {
                DLog.i("Idle: closing shell session (reconnects on demand)")
                teardown()
                state = State.DISCONNECTED
                notifyChanged()
            }
        }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        // If mouse mode was on the last time the app ran, connect and turn it back
        // on automatically instead of waiting for the user to tap it again.
        if (prefs.wasMouseOn) {
            enableAfterConnect = true
            connect()
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { main.post { listeners.forEach { it() } } }

    fun toast(msg: String) { main.post { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() } }

    // ------------------------------------------------------------------ connection

    fun connect() {
        if (state == State.CONNECTING) return
        state = State.CONNECTING
        lastError = null
        notifyChanged()
        io.execute {
            var created: ShellBackend? = null
            try {
                teardown()
                val b = createBackend().also { created = it }
                b.onDisconnected = { onBackendLost("Lost connection to ${b.label}") }
                DLog.i("Connecting via ${b.label}")
                b.connect()
                b.startHid()
                b.writeHid(HidMouse.registerJson())
                backend = b
                engine = CursorEngine(b, prefs) { t -> onBackendLost("Write error: ${t.message}") }
                state = State.CONNECTED
                DLog.i("Virtual HID mouse created. Toggle mouse mode with the mapped key.")
                if (DebugTools.ENABLED) DebugTools.onAdbConnected(appContext)
                grantSecureSettings(b)
                if (enableAfterConnect) setMouseMode(true) else scheduleIdleDisconnect()
            } catch (t: Throwable) {
                DLog.e("Connection failed: ${t.message}", t)
                // A half-open backend was never stored in `backend`, so teardown() can't see it: close it
                // here or the socket + reader thread leak on every failed attempt.
                runCatching { created?.close() }
                lastError = t.message ?: t.javaClass.simpleName
                teardown()
                state = State.DISCONNECTED
                toast("Error: $lastError")
            } finally {
                enableAfterConnect = false
            }
            notifyChanged()
        }
    }

    fun disconnect() {
        prefs.wasMouseOn = false // an explicit disconnect also cancels auto-resume on the next launch
        io.execute {
            teardown()
            state = State.DISCONNECTED
            DLog.i("Disconnected")
            notifyChanged()
        }
    }

    private fun createBackend(): ShellBackend = AdbBackend(appContext, prefs.host, prefs.port)

    private fun teardown() {
        mouseModeOn = false
        stopWatching()
        main.removeCallbacks(idleRunnable)
        engine?.shutdown()
        engine = null
        runCatching { backend?.close() }
        backend = null
    }

    private fun onBackendLost(reason: String) {
        if (state == State.DISCONNECTED) return
        DLog.w(reason)
        mouseModeOn = false
        io.execute {
            teardown()
            state = State.DISCONNECTED
            lastError = reason
            notifyChanged()
            toast(reason)
        }
    }

    private fun scheduleIdleDisconnect() {
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, IDLE_DISCONNECT_MS)
    }

    // ------------------------------------------------------------------ mouse mode

    fun toggleFromService() {
        when (state) {
            State.CONNECTED -> setMouseMode(!mouseModeOn)
            State.CONNECTING -> toast("Connecting…")
            State.DISCONNECTED -> {
                toast("Connecting to shell…")
                enableAfterConnect = true
                connect()
            }
        }
    }

    fun setMouseMode(on: Boolean) {
        if (on && state != State.CONNECTED) return
        if (mouseModeOn == on) {
            // Already ON but the service is dead (disabled / crashed / stale): just repair it. This is what
            // used to need an OFF tap followed by an ON tap.
            if (on) ensureService()
            return
        }
        mouseModeOn = on
        prefs.wasMouseOn = on // remembered so the app can resume automatically next launch
        if (on) {
            main.removeCallbacks(idleRunnable)
            startWatching()
            ensureService()
        } else {
            engine?.releaseAll()
            stopWatching()
            scheduleIdleDisconnect()
        }
        DLog.i("Mouse mode: ${if (on) "ON" else "OFF"}")
        toast(if (on) "Mouse: ON" else "Mouse: OFF")
        notifyChanged()
    }

    private fun ensureService() {
        if (serviceConnected) return
        enableAccessibilityService { r ->
            r.onFailure {
                DLog.w("Enable accessibility failed: ${it.message}")
                toast("Accessibility: ${it.message}")
            }
        }
    }

    fun attachService() {
        svcLock.withLock {
            serviceConnected = true
            svcCond.signalAll()
        }
        notifyChanged()
    }

    fun detachService() {
        // Only note that keys are no longer captured. Do NOT turn mouse mode off here: the service
        // also unbinds briefly while enableAccessibilityService() re-binds it, and that must not
        // cancel the user's choice (or the saved "resume on launch" state).
        serviceConnected = false
        engine?.releaseAll()
        notifyChanged()
    }

    private fun startWatching() {
        synchronized(this) {
            if (watching) return
            watching = true
        }
        appContext.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11yObserver
        )
    }

    private fun stopWatching() {
        synchronized(this) {
            if (!watching) return
            watching = false
        }
        appContext.contentResolver.unregisterContentObserver(a11yObserver)
        main.removeCallbacks(recoverRunnable)
    }

    // ------------------------------------------------------------------ one-off shell commands

    /** Runs a command (blocks the calling thread). Uses the open session if any, otherwise opens a temporary one. */
    fun execSync(command: String): String {
        val existing = backend
        if (existing != null) return existing.exec(command)
        val b = createBackend()
        try {
            b.connect()
            return b.exec(command)
        } finally {
            runCatching { b.close() }
        }
    }

    /** Like [execSync] but non-blocking; the callback runs on the main thread. */
    fun execOnce(command: String, callback: (Result<String>) -> Unit) {
        io.execute {
            val result = runCatching { execSync(command) }
            main.post { callback(result) }
        }
    }

    // ------------------------------------------------------------------ accessibility enabling

    private fun canWriteSecure(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** One-time grant over ADB: afterwards the app can flip its own service without any shell session. */
    private fun grantSecureSettings(b: ShellBackend) {
        if (canWriteSecure()) return
        try {
            val out = b.exec("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS && echo OK")
            DLog.i("Grant WRITE_SECURE_SETTINGS: $out")
        } catch (t: Throwable) {
            DLog.w("Could not grant WRITE_SECURE_SETTINGS: ${t.message}")
        }
    }

    private val myComponent by lazy { ComponentName(appContext, MouseAccessibilityService::class.java) }

    private fun enabledServices(): List<String> =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }

    /** Compared as components so both the long and short ("pkg/.Cls") spellings of our entry match. */
    private fun isListed(): Boolean =
        enabledServices().any { ComponentName.unflattenFromString(it) == myComponent }

    private fun writeEnabledServices(list: List<String>, enable: Boolean) {
        val value = list.joinToString(":")
        if (canWriteSecure()) {
            try {
                val cr = appContext.contentResolver
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
                if (enable) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                return
            } catch (e: SecurityException) {
                DLog.w("Direct settings write refused, using shell: ${e.message}")
            }
        }
        val cmd = "settings put secure enabled_accessibility_services '$value'" +
            (if (enable) " && settings put secure accessibility_enabled 1" else "") + " && echo OK"
        val out = execSync(cmd)
        if (!out.contains("OK")) throw IOException(out.ifBlank { "settings put failed" })
    }

    private fun awaitService(timeoutMs: Long): Boolean {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        svcLock.lock()
        try {
            while (!serviceConnected) {
                if (nanos <= 0L) return false
                nanos = svcCond.awaitNanos(nanos)
            }
            return true
        } finally {
            svcLock.unlock()
        }
    }

    /**
     * Enables the app's accessibility service and WAITS until it is really bound.
     *
     * Android only (re)binds a service when the setting value actually CHANGES. If our entry is already
     * listed but the service isn't running (stale binding after an update, crash, force-stop), re-writing
     * the same list does nothing, so the list is first written without us, then with us. Each attempt is
     * verified via onServiceConnected and retried with longer gaps on slow boxes. Concurrent callers share
     * one run (before, rapid taps interleaved remove/add cycles and unbound the service just bound).
     */
    fun enableAccessibilityService(callback: (Result<String>) -> Unit = {}) {
        if (serviceConnected) {
            main.post { callback(Result.success("running")) }
            return
        }
        synchronized(a11yWaiters) {
            a11yWaiters.add(callback)
            if (a11yBusy) return
            a11yBusy = true
        }
        a11yExec.execute {
            val result = runCatching { doEnableAccessibility() }
            val waiters = synchronized(a11yWaiters) {
                a11yBusy = false
                a11yWaiters.toList().also { a11yWaiters.clear() }
            }
            main.post { waiters.forEach { it(result) } }
        }
    }

    private fun doEnableAccessibility(): String {
        val me = myComponent.flattenToString()
        for (attempt in 0 until A11Y_ATTEMPTS) {
            if (serviceConnected) return "running"
            val current = enabledServices()
            val others = current.filter { ComponentName.unflattenFromString(it) != myComponent }
            val listed = others.size != current.size
            if (listed || attempt > 0) {
                writeEnabledServices(others, enable = false)
                Thread.sleep(A11Y_REBIND_GAP_MS * (attempt + 1))
            }
            writeEnabledServices(others + me, enable = true)
            if (awaitService(A11Y_BIND_TIMEOUT_MS + attempt * 1_500L)) return "OK"
            DLog.w("Accessibility did not bind (attempt ${attempt + 1}/$A11Y_ATTEMPTS)")
        }
        throw IOException("Accessibility service did not start")
    }

    private const val A11Y_ATTEMPTS = 3
    private const val A11Y_REBIND_GAP_MS = 400L
    private const val A11Y_BIND_TIMEOUT_MS = 3_000L
    private const val RECOVER_DELAY_MS = 1_000L
    private const val RECOVER_WINDOW_MS = 30_000L
    private const val RECOVER_MAX = 3
    private const val IDLE_DISCONNECT_MS = 5 * 60_000L
}
