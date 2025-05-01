package moe.shizuku.manager.starter

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbWirelessHelper
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.application
import moe.shizuku.manager.databinding.StarterActivityBinding
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku

private class NotRootedException : Exception()

class StarterActivity : AppBarActivity() {

    private val viewModel by viewModels {
        ViewModel(
            this,
            intent.getBooleanExtra(EXTRA_IS_ROOT, true),
            intent.getStringExtra(EXTRA_HOST),
            intent.getIntExtra(EXTRA_PORT, 0)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)

        val binding = StarterActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.output.observe(this) {
            val output = it.data!!.trim()
            if (output.endsWith("info: shizuku_starter exit with 0")) {
                viewModel.appendOutput("")
                viewModel.appendOutput("Waiting for service...")

                Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                    override fun onBinderReceived() {
                        Shizuku.removeBinderReceivedListener(this)
                        viewModel.appendOutput(
                            "Service started, this window will be automatically closed in 3 seconds"
                        )

                        window?.decorView?.postDelayed({ if (!isFinishing) finish() }, 3000)
                    }
                })
            } else if (it.status == Status.ERROR) {
                var message = 0
                when (it.error) {
                    is AdbKeyException -> {
                        message = R.string.adb_error_key_store
                    }

                    is NotRootedException -> {
                        message = R.string.start_with_root_failed
                    }

                    is ConnectException -> {
                        message = R.string.cannot_connect_port
                    }

                    is SSLProtocolException -> {
                        message = R.string.adb_pair_required
                    }
                }

                if (message != 0) {
                    MaterialAlertDialogBuilder(this).setMessage(message)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
            binding.text1.text = output
        }
    }

    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_HOST = "$EXTRA.HOST"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

private class ViewModel(context: Context, root: Boolean, host: String?, port: Int) :
    androidx.lifecycle.ViewModel() {

    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()
    private val adbWirelessHelper = AdbWirelessHelper()

    val output = _output as LiveData<Resource<StringBuilder>>

    init {
        try {
            if (root) {
                // Starter.writeFiles(context)
                startRoot()
            } else {
                startAdb(context, host!!, port)
            }
        } catch (e: Throwable) {
            postResult(e)
        }
    }

    fun appendOutput(line: String) {
        sb.appendLine(line)
        postResult()
    }

    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null) _output.postValue(Resource.success(sb))
        else _output.postValue(Resource.error(throwable, sb))
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startRoot() {
        sb.append("Starting with root...").append('\n').append('\n')
        postResult()

        GlobalScope.launch(Dispatchers.IO) {
            if (!Shell.rootAccess()) {
                Shell.getCachedShell()?.close()
                sb.append('\n').append("Can't open root shell, try again...").append('\n')

                postResult()
                if (!Shell.rootAccess()) {
                    sb.append('\n').append("Still not :(").append('\n')
                    postResult(NotRootedException())
                    return@launch
                }
            }

            Starter.writeDataFiles(application)
            Shell.su(Starter.dataCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    sb.append(s).append('\n')
                    postResult()
                }
            }).submit {
                if (it.code != 0) {
                    sb.append('\n').append("Send this to developer may help solve the problem.")
                    postResult()
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startAdb(context: Context, host: String, port: Int) {
        sb.append("Starting with wireless adb...").append('\n').append('\n')
        postResult()

        adbWirelessHelper.startShizukuViaAdb(
            context = context,
            host = host,
            port = port,
            coroutineScope = GlobalScope,
            onOutput = { outputString ->
                sb.append(outputString)
                postResult()
            },
            onError = { e -> postResult(e) })
    }
}
