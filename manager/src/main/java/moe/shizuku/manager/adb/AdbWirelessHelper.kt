package moe.shizuku.manager.adb

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity

class AdbWirelessHelper {

    private val adbWifiKey: String = "adb_wifi_enabled"

    fun validateThenEnableWirelessAdb(contentResolver: ContentResolver, context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities =
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            enableWirelessADB(contentResolver, context)
            return true
        } else {
            Log.w(AppConstants.TAG, "Wireless ADB auto-start condition not met: Not on Wi-Fi.")
        }
        return false
    }

    private fun enableWirelessADB(contentResolver: ContentResolver, context: Context) {
        // Enable wireless ADB
        try {
            Settings.Global.putInt(contentResolver, adbWifiKey, 1)
            Log.i(AppConstants.TAG, "Wireless Debugging enabled via secure setting.")
            Toast.makeText(context, "Wireless Debugging enabled", Toast.LENGTH_SHORT).show()
        } catch (se: SecurityException) {
            Log.e(AppConstants.TAG, "Permission denied trying to enable wireless debugging.", se)
            throw se
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "Error enabling wireless debugging.", e)
            throw e
        }
    }

    fun launchStarterActivity(context: Context, host: String, port: Int) {
        val intent = Intent(context, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_HOST, host)
            putExtra(StarterActivity.EXTRA_PORT, port)
        }
        context.startActivity(intent)
    }

    fun startShizukuViaAdb(
        context: Context,
        host: String,
        port: Int,
        coroutineScope: CoroutineScope,
        onOutput: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(AppConstants.TAG, "Attempting to start Shizuku via ADB on $host:$port")
                Starter.writeSdcardFiles(context)

                val key = try {
                    AdbKey(
                        PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku"
                    )
                } catch (e: Throwable) {
                    Log.e(AppConstants.TAG, "ADB Key error", e)
                    onError(AdbKeyException(e))
                    return@launch
                }

                val commandOutput = StringBuilder()

                AdbClient(host, port, key).use { client ->
                    try {
                        client.connect()
                        Log.i(
                            AppConstants.TAG,
                            "ADB connected to $host:$port. Executing starter command..."
                        )

                        client.shellCommand(Starter.sdcardCommand) { output ->
                            val outputString = String(output)
                            commandOutput.append(outputString)
                            onOutput(outputString)
                            Log.d(AppConstants.TAG, "Shizuku start output chunk: $outputString")
                        }

                        /* Adb on MIUI Android 11 has no permission to access Android/data.
                          Before MIUI Android 12, we can temporarily use /data/user_de.
                          After that, is better to implement "adb push" and push files directly to /data/local/tmp.
                        */
                        if (commandOutput.contains(
                                "/Android/data/${BuildConfig.APPLICATION_ID}/start.sh: Permission denied"
                            )
                        ) {
                            Log.w(
                                AppConstants.TAG,
                                "Detected permission issue, attempting fallback using /data/user_de"
                            )

                            val miuiMessage =
                                "\n" + "adb have no permission to access Android/data, how could this possible ?!\n" + "try /data/user_de instead...\n" + "\n"
                            onOutput(miuiMessage)

                            Starter.writeDataFiles(context, true) // Write to data with permissions

                            AdbClient(host, port, key).use { fallbackClient ->
                                fallbackClient.connect()
                                Log.i(
                                    AppConstants.TAG,
                                    "ADB reconnected for fallback. Executing data command..."
                                )

                                fallbackClient.shellCommand(Starter.dataCommand) { output ->
                                    val outputString = String(output)
                                    onOutput(outputString)
                                    Log.d(
                                        AppConstants.TAG,
                                        "Shizuku fallback start output chunk: $outputString"
                                    )
                                }

                                Log.i(AppConstants.TAG, "Shizuku fallback start command finished.")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(AppConstants.TAG, "Error during ADB connection/command execution", e)
                        onError(e)
                        return@launch
                    }
                }

                Log.i(AppConstants.TAG, "Shizuku start via ADB completed successfully")
                onSuccess()
            } catch (e: Throwable) {
                Log.e(AppConstants.TAG, "Error in startShizukuViaAdb", e)
                onError(e)
            }
        }
    }
}
