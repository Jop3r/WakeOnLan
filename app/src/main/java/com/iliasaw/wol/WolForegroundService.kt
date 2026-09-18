package com.iliasaw.wol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class WolState(
    val running: Boolean = false,
    val ssid: String? = null,
    val ipPrefix: String? = null,
    val home: Boolean? = null,
    val lastResult: String? = null,
    val lastSentAt: Long? = null,
)

/**
 * Постоянный сервис: следит за домашней Wi-Fi сетью и при «приходе» отправляет
 * WOL-пакет. «Приход» = телефон подключён к домашней сети (по подсети — главный
 * признак, или по SSID — запасной) и был вне её дольше «минимального отрыва»
 * (N минут). Пока телефон дома, время последнего посещения обновляется, поэтому
 * короткие переподключения Wi-Fi комп не будят. SSID опционален: без него
 * геолокация и её разрешения не нужны вовсе.
 */
class WolForegroundService : Service() {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var prefs: Prefs
    private var cm: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetwork: Network? = null

    companion object {
        private const val TAG = "WolService"
        private const val CHANNEL_ID = "wol_service"
        private const val NOTIFICATION_ID = 1
        private const val SEND_ATTEMPTS = 4
        private const val SEND_ATTEMPT_DELAY_MS = 500L
        private const val CONNECT_SETTLE_DELAY_MS = 1500L
        private const val RECHECK_INTERVAL_SEC = 10L

        @Volatile
        var state: WolState = WolState()
            private set

        @Volatile
        var listener: ((WolState) -> Unit)? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WolForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WolForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        cm = getSystemService(ConnectivityManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        registerCallback()
        update { it.copy(running = true) }
        // если телефон уже подключён к домашней сети — проверяем сразу
        executor.execute {
            try {
                checkConnectedOnExecutor()
            } catch (_: Exception) {
            }
        }
        // перепроверка подключённой сети — страховка от пропущенных событий
        executor.scheduleWithFixedDelay({
            try {
                checkConnectedOnExecutor()
            } catch (_: Exception) {
            }
        }, RECHECK_INTERVAL_SEC, RECHECK_INTERVAL_SEC, TimeUnit.SECONDS)
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        networkCallback = null
        executor.shutdownNow()
        update { it.copy(running = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun update(transform: (WolState) -> WolState) {
        WolForegroundService.state = transform(WolForegroundService.state)
        listener?.let { l ->
            val snapshot = WolForegroundService.state
            mainHandler.post { l(snapshot) }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wake on LAN", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = buildNotification(watchingText(), null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun watchingText(): String =
        getString(R.string.notif_watching, prefs.subnet.ifBlank { prefs.ssid.ifBlank { "—" } })

    private fun buildNotification(text: String, lastResult: String?): Notification {
        val full = if (lastResult != null) "$text\n$lastResult" else text
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(full)
            .setOngoing(true)
            .build()
    }

    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val handler = CallbackHandler(this)
        networkCallback = if (Build.VERSION.SDK_INT >= 31) {
            CallbackWithFlags(handler)
        } else {
            CallbackPlain(handler)
        }
        networkCallback?.let { cm?.registerNetworkCallback(request, it) }
    }

    /** Реакция на события сети, общая для callback'а с флагом и без. */
    private class CallbackHandler(private val service: WolForegroundService) {
        fun onAvailable(network: Network) = service.onWifiAvailable(network)
        fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            service.onWifiCapabilities(network, caps)
        fun onLost(network: Network) = service.onWifiLost(network)
    }

    private class CallbackWithFlags(private val handler: CallbackHandler) :
        ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
        override fun onAvailable(network: Network) = handler.onAvailable(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            handler.onCapabilitiesChanged(network, caps)
        override fun onLost(network: Network) = handler.onLost(network)
    }

    private class CallbackPlain(private val handler: CallbackHandler) : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handler.onAvailable(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            handler.onCapabilitiesChanged(network, caps)
        override fun onLost(network: Network) = handler.onLost(network)
    }

    /** Вызывается на главном потоке из callback'а. */
    private fun onWifiAvailable(network: Network) {
        wifiNetwork = network
        Log.d(TAG, "Wi-Fi доступна: $network")
        // страховка: проверяем сеть напрямую через небольшую задержку,
        // даже если onCapabilitiesChanged не пришёл
        executor.execute {
            try {
                Thread.sleep(CONNECT_SETTLE_DELAY_MS)
                checkAndSend(network)
            } catch (_: Exception) {
            }
        }
    }

    /** Вызывается на главном потоке из callback'а. */
    private fun onWifiCapabilities(network: Network, caps: NetworkCapabilities) {
        if (network != wifiNetwork) wifiNetwork = network
        executor.execute {
            try {
                checkAndSend(network)
            } catch (_: Exception) {
            }
        }
    }

    /** Вызывается на главном потоке из callback'а. */
    private fun onWifiLost(network: Network) {
        Log.d(TAG, "Wi-Fi потеряна: $network")
        if (network == wifiNetwork) {
            wifiNetwork = null
            update { it.copy(ssid = null, home = false) }
        }
    }

    /** Определяет «дома ли телефон» и при приходе после отрыва отправляет WOL. */
    private fun checkAndSend(network: Network) {
        val connMgr = cm ?: return
        val caps = connMgr.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val lp = connMgr.getLinkProperties(network)
        val phoneSubnet = WolSender.phoneSubnet(lp)
        val ssid = CurrentWifi.ssid(this)
        update { it.copy(ssid = ssid, ipPrefix = phoneSubnet) }

        // главный признак — подсеть, запасной — SSID
        val bySubnet = prefs.subnet.isNotBlank() && phoneSubnet != null &&
            WolSender.matchesSubnet(prefs.subnet, lp)
        val bySsid = prefs.ssid.isNotBlank() && ssid != null &&
            ssid.equals(prefs.ssid, ignoreCase = true)
        if (!bySubnet && !bySsid) {
            Log.d(TAG, "Не домашняя сеть: подсеть=$phoneSubnet, ssid=$ssid")
            update { it.copy(home = false) }
            return
        }

        // «минимальный отрыв»: дома время последнего посещения обновляется,
        // поэтому короткие переподключения Wi-Fi не будят
        val now = System.currentTimeMillis()
        val gap = now - prefs.lastHomeSeenAt
        prefs.lastHomeSeenAt = now
        if (gap < prefs.minAwayMinutes * 60_000L) {
            Log.d(TAG, "Дома, отрыв ${gap / 1000} c — не будим")
            update { it.copy(home = true) }
            return
        }

        Log.d(TAG, "Приход домой (отрыв ${gap / 60000} мин) — отправляю WOL")
        val result = sendRepeated(network, lp)
        Log.d(TAG, "WOL: $result")
        update { it.copy(home = true, lastResult = result, lastSentAt = now) }
        notifyUpdate(result)
    }

    private fun checkConnectedOnExecutor() {
        val connMgr = cm ?: return
        for (network in connMgr.allNetworks) {
            val caps = connMgr.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            checkAndSend(network)
            break
        }
    }

    private fun ssidOf(caps: NetworkCapabilities): String? {
        val info = caps.transportInfo as? WifiInfo ?: return null
        val ssid = info.ssid?.removeSurrounding("\"")
        return if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null else ssid
    }

    private fun sendRepeated(network: Network?, lp: LinkProperties?): String {
        var result = ""
        repeat(SEND_ATTEMPTS) { attempt ->
            result = WolSender.send(prefs.mac, prefs.port, network, lp)
            if (attempt < SEND_ATTEMPTS - 1) Thread.sleep(SEND_ATTEMPT_DELAY_MS)
        }
        return result
    }

    private fun notifyUpdate(lastResult: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(watchingText(), lastResult))
    }
}
