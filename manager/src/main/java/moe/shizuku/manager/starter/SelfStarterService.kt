package moe.shizuku.manager.starter

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbWirelessHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.net.ConnectException

class SelfStarterService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val portLive = MutableLiveData<Int>()
    private var adbMdns: AdbMdns? = null
    private val adbWirelessHelper = AdbWirelessHelper()

    private val portObserver = Observer<Int> { p ->
        if (p in 1..65535) {
            Log.i(
                AppConstants.TAG, "Discovered adb port via mDNS: $p, starting Shizuku directly"
            )
            // Do not launch activity, start ADB connection directly
            startShizukuViaAdb("127.0.0.1", p)
        } else {
            Log.w(AppConstants.TAG, "mDNS returned invalid port: $p")
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Log.i(AppConstants.TAG, "SelfStarterService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        Log.i(AppConstants.TAG, "SelfStarterService starting command")

        // Already running? Bail out.
        if (Shizuku.pingBinder()) {
            Log.i(AppConstants.TAG, "Shizuku is already running, stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        val wirelessEnabled = Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
        Log.d(AppConstants.TAG, "Wireless Debugging enabled setting: $wirelessEnabled")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wirelessEnabled) {
            Log.i(AppConstants.TAG, "Starting mDNS discovery for wireless ADB port.")

            // Remove potential previous observer before adding a new one
            portLive.removeObserver(portObserver)
            portLive.observeForever(portObserver)

            if (adbMdns == null) {
                adbMdns =
                    AdbMdns(context = this, serviceType = AdbMdns.TLS_CONNECT, port = portLive)
            }
            adbMdns?.start()
        } else {
            Log.i(
                AppConstants.TAG,
                "Using fallback: SystemProperties for ADB port (or wireless debugging setting off)."
            )
            val port = EnvironmentUtils.getAdbTcpPort()
            if (port > 0) {
                Log.i(
                    AppConstants.TAG,
                    "Found adb port via SystemProperties: $port, starting Shizuku directly."
                )
                startShizukuViaAdb("127.0.0.1", port)
            } else {
                Log.e(
                    AppConstants.TAG,
                    "Could not determine ADB TCP port via SystemProperties, aborting."
                )
                stopSelf()
            }
        }

        // Service should only run once per trigger
        return START_NOT_STICKY
    }

    private fun startShizukuViaAdb(host: String, port: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@SelfStarterService, "Starting Shizuku service…", Toast.LENGTH_SHORT)
                .show()
        }

        adbWirelessHelper.startShizukuViaAdb(
            context = applicationContext,
            host = host,
            port = port,
            coroutineScope = lifecycleScope,
            onOutput = { /* No UI to update in service */ },
            onError = { e ->
                lifecycleScope.launch(Dispatchers.Main) {
                    when (e) {
                        is AdbKeyException -> Toast.makeText(
                            applicationContext,
                            "ADB Key error during Shizuku start",
                            Toast.LENGTH_LONG
                        ).show()

                        is ConnectException -> Toast.makeText(
                            applicationContext,
                            "ADB Connection failed to $host:$port",
                            Toast.LENGTH_LONG
                        ).show()

                        else -> Toast.makeText(
                            applicationContext, "Error: ${e.message}", Toast.LENGTH_LONG
                        ).show()
                    }
                    stopSelf()
                }
            },
            onSuccess = { lifecycleScope.launch(Dispatchers.Main) { stopSelf() } })
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.i(AppConstants.TAG, "SelfStarterService destroying")
            adbMdns?.stop()
        }

        portLive.removeObserver(portObserver) // Clean up the observer
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
