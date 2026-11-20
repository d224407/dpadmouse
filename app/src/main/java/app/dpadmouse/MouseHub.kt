package app.dpadmouse

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import app.dpadmouse.hid.HidMouse
import app.dpadmouse.service.CursorEngine
import app.dpadmouse.service.EvGrab
import app.dpadmouse.service.KeepAliveService
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
        set(v) {
            field = v
            grab?.refresh()
        }

    /** EVIOCGRAB session (only while [Prefs.grabOnly] and connected). */
    @Volatile var grab: EvGrab? = null
        private set

    /** Whether keys are being captured: the EVIOCGRAB helper or the accessibility service, per the mode. */
    val keysReady: Boolean get() = if (prefs.grabOnly) grab != null else serviceActive()

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
        if (!mouseModeOn || prefs.grabOnly || serviceActive() || a11yBusy) return@Runnable
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
                if (prefs.grabOnly) startGrab(b)
                state = State.CONNECTED
                DLog.i("Virtual HID mouse created. Toggle mouse mode with the mapped key.")
                if (DebugTools.ENABLED) DebugTools.onAdbConnected(appContext)
                grantSecureSettings(b)
                allowBackground(b)
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
        stopKeepAlive()
        stopWatching()
        main.removeCallbacks(idleRunnable)
        grab?.stop()
        grab = null
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
        if (prefs.grabOnly) return // the helper must stay up to catch the on/off key
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
            if (on && !prefs.grabOnly) ensureService()
            return
        }
        mouseModeOn = on
        prefs.wasMouseOn = on // remembered so the app can resume automatically next launch
        if (on) {
            main.removeCallbacks(idleRunnable)
            startKeepAlive()
            if (prefs.grabOnly) {
                grab?.refresh()
            } else {
                startWatching()
                ensureService()
            }
        } else {
            engine?.releaseAll()
            stopKeepAlive()
            stopWatching()
            grab?.refresh()
            scheduleIdleDisconnect()
        }
        DLog.i("Mouse mode: ${if (on) "ON" else "OFF"}")
        toast(if (on) "Mouse: ON" else "Mouse: OFF")
        notifyChanged()
    }

    /** Keeps the process at foreground priority while mouse mode is on (see KeepAliveService). */
    private fun startKeepAlive() {
        try {
            appContext.startForegroundService(Intent(appContext, KeepAliveService::class.java))
        } catch (t: Throwable) {
            DLog.w("Keep-alive service not started: ${t.message}") // e.g. background start not allowed
        }
    }

    private fun stopKeepAlive() {
        runCatching { appContext.stopService(Intent(appContext, KeepAliveService::class.java)) }
    }

    /** Starts the EVIOCGRAB helper. Never fatal: the ADB connection and the virtual mouse stay up if it fails. */
    private fun startGrab(b: ShellBackend) {
        if (grab != null) return
        val g = EvGrab(appContext, b, io) { onGrabLost() }
        try {
            g.start()
            grab = g
        } catch (e: EvGrab.NoRemote) {
            g.stop()
            toast(Strings.mapKeyFirst)
        } catch (t: Throwable) {
            g.stop()
            DLog.w("EVIOCGRAB failed: ${t.message}")
            toast(Strings.grabFailed(t.message ?: t.javaClass.simpleName))
        }
        notifyChanged()
    }

    private fun onGrabLost() {
        if (grab == null) return
        grab?.stop()
        grab = null
        toast(Strings.grabFailed("helper stopped"))
        notifyChanged()
    }

    /** Called after the key mapping changed; also starts the helper once a remote has been chosen. */
    fun refreshGrabKeys() {
        val g = grab
        if (g != null) {
            g.refresh()
        } else if (prefs.grabOnly && state == State.CONNECTED) {
            val b = backend ?: return
            io.execute { startGrab(b) }
        }
    }

    /** Switches the key blocking method; reconnects if needed so the new method takes effect at once. */
    fun setGrabOnly(on: Boolean) {
        if (prefs.grabOnly == on) return
        prefs.grabOnly = on
        stopWatching()
        val resume = mouseModeOn
        if (on) {
            // Release the accessibility service: it is not needed in this mode.
            a11yExec.execute {
                runCatching {
                    writeEnabledServices(enabledServices().filter { ComponentName.unflattenFromString(it) != myComponent }, false)
                }
            }
        } else if (state == State.CONNECTED || canWriteSecure()) {
            ensureService()
        }
        if (state == State.CONNECTED) {
            enableAfterConnect = resume
            connect()
        }
        notifyChanged()
    }

    private fun ensureService() {
        if (prefs.grabOnly || serviceActive()) return
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

    /**
     * Puts the app on the battery-optimization allowlist over ADB. Besides keeping the process from being
     * throttled, Android 12+ lets an allowlisted app start its foreground service from the background
     * (e.g. when mouse mode is switched on with the remote while another app is in front).
     */
    private fun allowBackground(b: ShellBackend) {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val pkg = appContext.packageName
        if (pm.isIgnoringBatteryOptimizations(pkg)) return
        try {
            val out = b.exec("cmd deviceidle whitelist +$pkg; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            DLog.i("Battery allowlist: ${out.trim()}")
        } catch (t: Throwable) {
            DLog.w("Could not allow background: ${t.message}")
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

    /** Whether the system itself reports our service as bound (not just our own flag). */
    private fun isBoundBySystem(): Boolean {
        val am = appContext.getSystemService(AccessibilityManager::class.java) ?: return serviceConnected
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo?.serviceInfo
            si != null && si.packageName == myComponent.packageName && si.name == myComponent.className
        }
    }

    /**
     * True only while the service is really running. The connected flag alone can go stale (service
     * disabled without an unbind callback), so it is checked against the system's bound-service list.
     */
    fun serviceActive(): Boolean {
        if (!serviceConnected) return false
        if (isBoundBySystem()) return true
        svcLock.withLock { serviceConnected = false }
        return false
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
        if (serviceActive()) {
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
            if (serviceActive()) return "running"
            val current = enabledServices()
            val others = current.filter { ComponentName.unflattenFromString(it) != myComponent }
            val listed = others.size != current.size
            if (listed || attempt > 0) {
                writeEnabledServices(others, enable = false)
                // Whatever the old binding did, only a fresh onServiceConnected may count from here.
                svcLock.withLock { serviceConnected = false }
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
