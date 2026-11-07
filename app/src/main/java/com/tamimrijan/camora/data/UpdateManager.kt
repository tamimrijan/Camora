package com.tamimrijan.camora.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tamimrijan.camora.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The newest release published on GitHub. */
data class ReleaseInfo(
    val version: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data class Ready(val info: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val message: String, val info: ReleaseInfo? = null) : UpdateState
}

/**
 * One tap updates from the project's GitHub releases.
 *
 * Privacy: this is the only network code in Camora, and it runs only when the user taps
 * Check for updates or Download. It sends a plain request for the latest release, with no
 * identifiers, and downloads the APK attached to that release. Nothing else is sent.
 */
class UpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore(' ')

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val info = fetchLatest()
                when {
                    info == null -> UpdateState.Failed("No release has been published yet")
                    isNewer(info.version, currentVersion) -> UpdateState.Available(info)
                    else -> {
                        dir.listFiles()?.forEach { it.delete() } // old downloads are no longer needed
                        UpdateState.UpToDate
                    }
                }
            } catch (t: Throwable) {
                UpdateState.Failed(friendly(t))
            }
        }
    }

    fun download(info: ReleaseInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            val file = File(dir, "Camora-${info.version}.apk")
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                val tmp = File(dir, "download.part")
                val c = open(info.apkUrl)
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastShown = 0f
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val f = if (total > 0) done.toFloat() / total else 0f
                            if (f - lastShown >= 0.01f) {
                                lastShown = f
                                _state.value = UpdateState.Downloading(info, f.coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                // Make sure this really is a Camora APK before offering to install it.
                val pkg = context.packageManager.getPackageArchiveInfo(tmp.path, 0)?.packageName
                if (pkg != context.packageName) {
                    tmp.delete()
                    error("The downloaded file is not a Camora update")
                }
                file.delete()
                tmp.renameTo(file)
                _state.value = UpdateState.Ready(info, file)
                install()
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(friendly(t), info)
            }
        }
    }

    /**
     * Opens Android's installer for the downloaded APK. The first time, Android asks the user
     * to allow Camora to install updates; after that it is one tap.
     */
    fun install() {
        val ready = _state.value as? UpdateState.Ready ?: return
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Camora-Android")
        val code = c.responseCode
        if (code == 404) throw NoSuchElementException()
        if (code !in 200..299) error("GitHub answered $code")
        return c
    }

    private fun fetchLatest(): ReleaseInfo? {
        val c = try {
            open(LATEST)
        } catch (_: NoSuchElementException) {
            return null
        }
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets")
        var url = ""
        var size = 0L
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                url = a.optString("browser_download_url"); size = a.optLong("size"); break
            }
        }
        if (url.isEmpty()) error("The latest release has no APK attached")
        return ReleaseInfo(
            version = tag.trimStart('v', 'V'),
            title = json.optString("name").ifBlank { "Camora $tag" },
            notes = cleanNotes(json.optString("body")),
            apkUrl = url,
            sizeBytes = size,
        )
    }

    private fun friendly(t: Throwable): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "No internet connection"
        is java.net.SocketTimeoutException -> "GitHub did not answer in time, try again"
        else -> t.message ?: "Something went wrong, try again"
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/tamimrijan/Camora/releases/latest"

        /** True when [remote] is a higher version than [local] (compares the numbers, e.g. 2.3.10 > 2.3.9). */
        fun isNewer(remote: String, local: String): Boolean {
            val r = Regex("\\d+").findAll(remote).map { it.value.toInt() }.toList()
            val l = Regex("\\d+").findAll(local).map { it.value.toInt() }.toList()
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        /** Release notes as plain text: drop markdown symbols and keep it short. */
        fun cleanNotes(md: String): String = md.lines()
            .map { it.replace(Regex("^#+\\s*"), "").replace("**", "").replace(Regex("^\\s*[-*]\\s+"), "• ").trim() }
            .filter { it.isNotEmpty() }
            .take(12)
            .joinToString("\n")
    }
}