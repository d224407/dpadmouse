package app.dpadmouse

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK and hands it to the system installer (user confirms). */
object Updater {
    class Release(val version: String, val url: String, val size: Long)

    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    /** Background check, at most every 6 hours; [onFound] runs on the main thread if a newer version exists. */
    fun check(prefs: Prefs, onFound: (Release) -> Unit) {
        if (BuildConfig.DEBUG || BuildConfig.UPDATE_REPO.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return
        Thread({
            runCatching { fetchLatest() }
                .onSuccess { rel ->
                    prefs.lastUpdateCheck = now
                    if (rel != null && isNewer(rel.version)) main.post { onFound(rel) }
                }
                .onFailure { DLog.w("Update check failed: ${it.message}") }
        }, "dpadmouse-update-check").apply { isDaemon = true; start() }
    }

    private fun fetchLatest(): Release? {
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "dpadmouse")
            if (c.responseCode == 404) return null // no release yet
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(json.getString("tag_name"), a.getString("browser_download_url"), a.optLong("size", -1))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun numbers(v: String): List<Int> =
        Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toInt() }.toList()

    private fun isNewer(tag: String): Boolean {
        val a = numbers(tag)
        val b = numbers(BuildConfig.VERSION_NAME)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Streams the APK straight into a PackageInstaller session, then the system asks the user to confirm. */
    fun install(context: Context, rel: Release, onError: (String) -> Unit) {
        val app = context.applicationContext
        Thread({
            val installer = app.packageManager.packageInstaller
            var id = -1
            try {
                id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                installer.openSession(id).use { session ->
                    val c = URL(rel.url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 15_000
                        c.readTimeout = 30_000
                        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                        c.inputStream.use { input ->
                            session.openWrite("update.apk", 0, rel.size).use { out ->
                                input.copyTo(out)
                                session.fsync(out)
                            }
                        }
                    } finally {
                        c.disconnect()
                    }
                    val pending = PendingIntent.getBroadcast(
                        app, id, Intent(app, InstallReceiver::class.java),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    session.commit(pending.intentSender)
                }
            } catch (t: Throwable) {
                if (id >= 0) runCatching { installer.abandonSession(id) }
                main.post { onError(t.message ?: t.javaClass.simpleName) }
            }
        }, "dpadmouse-update").apply { isDaemon = true; start() }
    }
}
