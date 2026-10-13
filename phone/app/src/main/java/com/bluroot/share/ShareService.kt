package com.bluroot.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ShareUiState(
    val running: Boolean = false,
    val busy: Boolean = false,
    val token: String = "",
    val status: String = "Idle",
    val detail: String = "",
    val addresses: String = "",
    val log: String = "",
    val rooted: Boolean? = null,
)

class ShareService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): ShareService = this@ShareService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val stopOnce = AtomicBoolean(false)
    private var listener: ((ShareUiState) -> Unit)? = null
    private var state = ShareUiState()
    private var sharing = false
    private var polling = false
    private var pauseBluetooth = true
    private var session = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Sharing", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        io.execute { recoverIfAbandoned() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = note("BluRoot", state.status)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTE_ID, notification)
        }
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START && !sharing) {
            sharing = true
            stopOnce.set(false)
            session += 1
            val mine = session
            pauseBluetooth = intent.getBooleanExtra(EXTRA_PAUSE, true)
            val port = intent.getIntExtra(EXTRA_PORT, 8877)
            val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
            val permissive = intent.getBooleanExtra(EXTRA_PERMISSIVE, true)
            publish(state.copy(running = true, busy = true, token = token, status = "Starting"))
            io.execute { startJob(mine, port, token, permissive) }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        requestStop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        polling = false
        if (instance === this) {
            instance = null
        }
        io.shutdownNow()
        super.onDestroy()
    }

    fun current(): ShareUiState = state

    fun setListener(callback: ((ShareUiState) -> Unit)?) {
        listener = callback
        callback?.let { main.post { it(state) } }
    }

    fun requestStop() {
        if (!stopOnce.compareAndSet(false, true)) {
            return
        }
        session += 1
        polling = false
        publish(state.copy(busy = true, status = "Stopping"))
        io.execute {
            restore()
            sharing = false
            publish(state.copy(running = false, busy = false, status = "Stopped", token = ""))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startJob(mine: Int, port: Int, token: String, permissive: Boolean) {
        if (!ensureRoot()) {
            sharing = false
            publish(state.copy(running = false, busy = false, rooted = false, status = "Root was denied"))
            return
        }
        val name = "${Build.MANUFACTURER} ${Build.MODEL}"
            .replace(Regex("[\\t\\r\\n']"), " ")
            .take(40)
        val apk = applicationInfo.sourceDir.replace("'", "")
        val jar = try {
            extractAsset("bluroot-proxy.jar").absolutePath.replace("'", "")
        } catch (error: Exception) {
            fail("Could not prepare the radio proxy", error.message.orEmpty())
            return
        }
        val radioScript = try {
            extractAsset("bluroot-radio.sh").absolutePath.replace("'", "")
        } catch (error: Exception) {
            fail("Could not prepare the radio handoff", error.message.orEmpty())
            return
        }
        val pauseFlag = if (pauseBluetooth) "1" else "0"
        val permissiveFlag = if (permissive) "1" else "0"
        val script = """
            set +e
            getenforce > /data/local/tmp/bluroot.selinux 2>/dev/null
            echo $pauseFlag > /data/local/tmp/bluroot.pause
            rm -f /data/local/tmp/bluroot.stop /data/local/tmp/bluroot.status /data/local/tmp/bluroot.log /data/local/tmp/bluroot.pid
            if [ "$permissiveFlag" = 1 ]; then
              setenforce 0
            fi
            if [ "$pauseFlag" = 1 ]; then
              sh '$radioScript' pause || exit 1
            fi
            rm -f /data/local/tmp/bluroot-proxy.jar
            if command -v unzip >/dev/null 2>&1; then
              unzip -p '$apk' assets/bluroot-proxy.jar > /data/local/tmp/bluroot-proxy.jar
            fi
            if [ ! -s /data/local/tmp/bluroot-proxy.jar ]; then
              cp '$jar' /data/local/tmp/bluroot-proxy.jar
            fi
            if [ ! -s /data/local/tmp/bluroot-proxy.jar ]; then
              echo "could not unpack the radio proxy" >&2
              exit 1
            fi
            chmod 644 /data/local/tmp/bluroot-proxy.jar
            if command -v setsid >/dev/null 2>&1; then
              CLASSPATH=/data/local/tmp/bluroot-proxy.jar setsid app_process /system/bin com.bluroot.share.proxy.ProxyMain --port $port --token '$token' --name '$name' --status /data/local/tmp/bluroot.status </dev/null >/data/local/tmp/bluroot.log 2>&1 &
            else
              CLASSPATH=/data/local/tmp/bluroot-proxy.jar app_process /system/bin com.bluroot.share.proxy.ProxyMain --port $port --token '$token' --name '$name' --status /data/local/tmp/bluroot.status </dev/null >/data/local/tmp/bluroot.log 2>&1 &
            fi
            sleep 0.2
        """.trimIndent()
        val launch = Shell.cmd(script).exec()
        if (mine != session) {
            return
        }
        if (!launch.isSuccess) {
            fail("Could not start the radio proxy", launch.out.joinToString("\n") + launch.err.joinToString("\n"))
            return
        }
        var ready: JSONObject? = null
        for (attempt in 0 until 80) {
            if (mine != session) {
                return
            }
            Thread.sleep(250)
            val parsed = readStatus() ?: continue
            ready = parsed
            applyStatus(parsed)
            val proxyState = parsed.optString("state")
            if (proxyState == "error" || proxyState == "waiting" || proxyState == "connected") {
                break
            }
        }
        if (mine != session) {
            return
        }
        val snapshot = ready
        if (snapshot == null) {
            val log = Shell.cmd("tail -n 40 /data/local/tmp/bluroot.log").exec()
            fail("The radio proxy did not start", log.out.joinToString("\n"))
            return
        }
        if (snapshot.optString("state") == "error") {
            val message = snapshot.optString("message").ifEmpty { "The radio is busy" }
            restore()
            sharing = false
            publish(state.copy(running = false, busy = false, status = message))
            return
        }
        publish(state.copy(busy = false, running = true))
        polling = true
        main.post { pollLoop() }
    }

    private fun pollLoop() {
        if (!polling) {
            return
        }
        io.execute {
            val parsed = readStatus()
            if (parsed != null) {
                applyStatus(parsed)
            }
            if (polling) {
                main.postDelayed({ pollLoop() }, 1000)
            }
        }
    }

    private fun applyStatus(json: JSONObject) {
        val proxyState = json.optString("state")
        val message = json.optString("message")
        val client = json.optString("client")
        val hal = json.optString("hal")
        val addresses = json.optJSONArray("addresses")
        val addressText = buildString {
            if (addresses == null || addresses.length() == 0) {
                append("No Wi-Fi address yet. USB still works.")
            } else {
                append("Wi-Fi ")
                for (i in 0 until addresses.length()) {
                    if (i > 0) append(", ")
                    append(addresses.optString(i))
                }
                append("  port ")
                append(json.optInt("port"))
            }
        }
        val detail = buildString {
            if (hal.isNotEmpty()) append("HAL ").append(hal)
            if (client.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append("PC ").append(client)
            }
            append("  ·  to radio ")
            append(json.optInt("tx"))
            append("  ·  to PC ")
            append(json.optInt("rx"))
        }
        val logLines = json.optJSONArray("log")
        val log = buildString {
            if (logLines != null) {
                for (i in 0 until logLines.length()) {
                    append(logLines.optString(i))
                    append('\n')
                }
            }
        }
        val status = if (message.isNotEmpty()) message else proxyState
        publish(
            state.copy(
                running = proxyState != "stopped" && proxyState != "error",
                token = json.optString("token", state.token),
                status = status,
                detail = detail,
                addresses = addressText,
                log = log.trim(),
                rooted = true,
            )
        )
        if (proxyState == "error") {
            polling = false
            sharing = false
        }
    }

    private fun extractAsset(name: String): File {
        val out = File(filesDir, name)
        assets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun fail(status: String, detail: String) {
        polling = false
        sharing = false
        restore()
        publish(
            state.copy(
                running = false,
                busy = false,
                status = status,
                detail = detail.trim(),
                rooted = true,
            )
        )
    }

    private fun recoverIfAbandoned() {
        val pid = Shell.cmd("cat /data/local/tmp/bluroot.pid").exec().out.firstOrNull()?.trim().orEmpty()
        val alive = pid.all { it.isDigit() } && pid.isNotEmpty() &&
            Shell.cmd("kill -0 $pid").exec().isSuccess
        val marked = Shell.cmd(
            "test -f /data/local/tmp/bluroot.selinux || test -f /data/local/tmp/bluroot.ble-scan || " +
                "test -f /data/local/tmp/bluroot.packages"
        ).exec().isSuccess
        if (!alive && marked && !sharing) {
            restore()
            publish(state.copy(status = "Restored Bluetooth after an interrupted share"))
        }
    }

    private fun restore() {
        val radioScript = extractAsset("bluroot-radio.sh").absolutePath.replace("'", "")
        val script = """
            set +e
            if [ -f /data/local/tmp/bluroot.pid ]; then
              pid=${'$'}(cat /data/local/tmp/bluroot.pid)
              touch /data/local/tmp/bluroot.stop
              i=0
              while [ "${'$'}i" -lt 8 ]; do
                kill -0 ${'$'}pid 2>/dev/null || break
                sleep 0.4
                i=${'$'}((i+1))
              done
              kill -9 ${'$'}pid 2>/dev/null
            fi
            # Restore background scanning only after the proxy releases the HAL.
            sh '$radioScript' restore
            if [ "${'$'}(cat /data/local/tmp/bluroot.pause 2>/dev/null)" = "1" ]; then
              cmd bluetooth_manager enable
            fi
            if [ "${'$'}(cat /data/local/tmp/bluroot.selinux 2>/dev/null)" = "Enforcing" ]; then
              setenforce 1
            fi
            rm -f /data/local/tmp/bluroot.pid /data/local/tmp/bluroot.stop /data/local/tmp/bluroot.status /data/local/tmp/bluroot.pause /data/local/tmp/bluroot.selinux
        """.trimIndent()
        Shell.cmd(script).exec()
    }

    private fun readStatus(): JSONObject? {
        val result = Shell.cmd("cat /data/local/tmp/bluroot.status").exec()
        val raw = (result.out + result.err).joinToString("\n")
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) {
            return null
        }
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureRoot(): Boolean {
        return try {
            val result = Shell.cmd("id").exec()
            val rooted = result.out.any { it.contains("uid=0") }
            publish(state.copy(rooted = rooted))
            rooted
        } catch (_: Exception) {
            publish(state.copy(rooted = false))
            false
        }
    }

    private fun publish(next: ShareUiState) {
        state = next
        val callback = listener
        val notification = note("BluRoot", next.status)
        main.post {
            callback?.invoke(next)
            if (sharing || next.running) {
                getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification)
            }
        }
    }

    private fun note(title: String, text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.bluroot.share.START"
        const val ACTION_STOP = "com.bluroot.share.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PAUSE = "pause"
        const val EXTRA_PERMISSIVE = "permissive"
        private const val CHANNEL = "sharing"
        private const val NOTE_ID = 7

        @Volatile
        var instance: ShareService? = null
    }
}
