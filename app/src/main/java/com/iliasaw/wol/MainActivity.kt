package com.iliasaw.wol

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.iliasaw.wol.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val stateListener: (WolState) -> Unit = { render(it) }

    private val pollRunnable = object : Runnable {
        override fun run() {
            render(WolForegroundService.state)
            mainHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editMac.setText(prefs.mac)
        binding.editSubnet.setText(prefs.subnet)
        binding.editSsid.setText(prefs.ssid)
        binding.editMinAway.setText(prefs.minAwayMinutes.toString())
        binding.editPort.setText(prefs.port.toString())

        binding.btnSend.setOnClickListener { sendNow() }
        binding.btnDetectSubnet.setOnClickListener { detectSubnet() }
        binding.btnUseCurrentSsid.setOnClickListener { useCurrentSsid() }
        binding.switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (checked) startMonitoring() else stopMonitoring()
        }

        requestNotificationPermission()
        render(WolForegroundService.state)
    }

    override fun onResume() {
        super.onResume()
        WolForegroundService.listener = stateListener
        render(WolForegroundService.state)
        mainHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        WolForegroundService.listener = null
        mainHandler.removeCallbacks(pollRunnable)
        saveFields()
    }

    private fun render(state: WolState) {
        binding.switchMonitor.setOnCheckedChangeListener(null)
        binding.switchMonitor.isChecked = state.running
        binding.switchMonitor.setOnCheckedChangeListener { _, checked ->
            if (checked) startMonitoring() else stopMonitoring()
        }

        binding.statusMain.text =
            if (state.running) getString(R.string.status_running) else getString(R.string.status_stopped)
        val netParts = listOfNotNull(state.ssid, state.ipPrefix)
        binding.statusNetwork.text = getString(
            R.string.status_network,
            if (netParts.isEmpty()) "—" else netParts.joinToString(" · ")
        )
        binding.statusHome.text = when (state.home) {
            true -> getString(R.string.status_home_yes)
            false -> getString(R.string.status_home_no)
            null -> getString(R.string.status_home_unknown)
        }
        val result = state.lastResult
        binding.statusResult.text = if (result != null) {
            val time = state.lastSentAt?.let { timeFormat.format(Date(it)) }.orEmpty()
            getString(R.string.status_last, time, result)
        } else {
            getString(R.string.status_last_none)
        }
    }

    private fun saveFields() {
        prefs.mac = binding.editMac.text.toString().trim()
        prefs.subnet = binding.editSubnet.text.toString().trim()
        prefs.ssid = binding.editSsid.text.toString().trim()
        prefs.minAwayMinutes = binding.editMinAway.text.toString().toIntOrNull()?.coerceIn(0, 1440)
            ?: Prefs.DEFAULT_MIN_AWAY
        prefs.port = binding.editPort.text.toString().toIntOrNull()?.coerceIn(1, 65535)
            ?: Prefs.DEFAULT_PORT
    }

    private fun startMonitoring() {
        saveFields()
        if (WolSender.parseMac(prefs.mac) == null) {
            toast(getString(R.string.error_bad_mac))
            render(WolForegroundService.state)
            return
        }
        if (prefs.subnet.isBlank() && prefs.ssid.isBlank()) {
            toast(getString(R.string.error_no_home))
            render(WolForegroundService.state)
            return
        }
        WolForegroundService.start(this)
    }

    private fun stopMonitoring() {
        saveFields()
        WolForegroundService.stop(this)
    }

    private fun sendNow() {
        saveFields()
        if (WolSender.parseMac(prefs.mac) == null) {
            toast(getString(R.string.error_bad_mac))
            return
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        var wifi: Network? = null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifi = network
                break
            }
        }
        val network = wifi
        val lp = network?.let { cm.getLinkProperties(it) }
        val mac = prefs.mac
        val port = prefs.port
        binding.statusResult.text = getString(R.string.status_sending)
        sendExecutor.execute {
            val result = WolSender.send(mac, port, network, lp)
            mainHandler.post {
                binding.statusResult.text = "${timeFormat.format(Date())} — $result"
                toast(result)
            }
        }
    }

    private fun detectSubnet() {
        val cm = getSystemService(ConnectivityManager::class.java)
        var lp: LinkProperties? = null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                lp = cm.getLinkProperties(network)
                break
            }
        }
        val subnet = WolSender.phoneSubnet(lp)
        if (subnet == null) {
            toast(getString(R.string.error_detect))
        } else {
            binding.editSubnet.setText(subnet)
            saveFields()
            toast("Подсеть: $subnet")
        }
    }

    private fun useCurrentSsid() {
        val result = CurrentWifi.current(this)
        when (result.reason) {
            CurrentWifi.Reason.OK -> {
                binding.editSsid.setText(result.ssid)
                saveFields()
                toast("SSID подставлен: ${result.ssid}")
            }
            CurrentWifi.Reason.PERMISSION -> {
                requestSsidPermissions()
                toast("Выдайте разрешение в диалоге и нажмите ещё раз")
            }
            CurrentWifi.Reason.NOT_ON_WIFI -> toast(getString(R.string.error_not_on_wifi))
            CurrentWifi.Reason.LOCATION_OFF -> toast(getString(R.string.error_location_off))
            CurrentWifi.Reason.HIDDEN -> toast(getString(R.string.error_hidden))
        }
    }

    /** Уведомления нужны для фонового сервиса; SSID-разрешения запрашиваются
     *  по требованию — только если пользователь хочет сопоставление по SSID. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun requestSsidPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                perms += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 2)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
