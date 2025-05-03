package moe.shizuku.manager.settings

import android.os.Process
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemProperties
import android.os.UserHandle
import android.os.UserManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.preference.*
import android.util.Log
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT_WIRELESS
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.manager.ShizukuLocales
import rikka.widget.borderview.BorderRecyclerView
import java.util.*
import moe.shizuku.manager.ShizukuSettings.LANGUAGE as KEY_LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE as KEY_NIGHT_MODE
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings.ADB_ROOT
import moe.shizuku.manager.ShizukuSettings.PENDING_SECURE_SETTINGS_GRANT
import moe.shizuku.manager.ktx.TAG
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.core.util.ClipboardUtils
import rikka.hidden.compat.UserManagerApis
import rikka.html.text.HtmlCompat
import rikka.shizuku.Shizuku
import kotlin.reflect.typeOf

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var languagePreference: ListPreference
    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var startOnBootWirelessPreference: TwoStatePreference
    private lateinit var adbRoot: TwoStatePreference
    private lateinit var startupPreference: PreferenceCategory
    private lateinit var translationPreference: Preference
    private lateinit var translationContributorsPreference: Preference
    private lateinit var useSystemColorPreference: TwoStatePreference
    private lateinit var bootComponentName: ComponentName

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        bootComponentName =
            ComponentName(context.packageName, BootCompleteReceiver::class.java.name)
        languagePreference = findPreference(KEY_LANGUAGE)!!
        nightModePreference = findPreference(KEY_NIGHT_MODE)!!
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME)!!
        startOnBootPreference = findPreference(KEEP_START_ON_BOOT)!!
        startOnBootWirelessPreference = findPreference(KEEP_START_ON_BOOT_WIRELESS)!!
        adbRoot = findPreference(ADB_ROOT)!!
        startupPreference = findPreference("startup")!!
        translationPreference = findPreference("translation")!!
        translationContributorsPreference = findPreference("translation_contributors")!!
        useSystemColorPreference = findPreference(KEY_USE_SYSTEM_COLOR)!!

        // User builds do not have rooted debugging
        adbRoot.isVisible = !Build.TYPE.equals("user", ignoreCase = true)

        // Initialize toggles based on saved preferences
        updatePreferenceStates()

        startOnBootPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    if (newValue) {
                        // These two options are mutually exclusive. So, disable the wireless option
                        startOnBootWirelessPreference.isChecked = false
                        savePreference(KEEP_START_ON_BOOT_WIRELESS, false)
                    }
                    toggleBootComponent(
                        KEEP_START_ON_BOOT, newValue
                    )
                } else false
            }

        startOnBootWirelessPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    if (newValue) {
                        // Check for permission
                        if (!hasSecureSettingsPermission()) {
                            Log.d(TAG, "WRITE_SECURE_SETTINGS permission not granted")

                            val grantPermission = tryToGrantSecureSettingsPermission()


                            if (grantPermission) {
                                // Disable the root option because of mutual exclusivity
                                startOnBootPreference.isChecked = false
                                savePreference(KEEP_START_ON_BOOT, false)

                                return@OnPreferenceChangeListener toggleBootComponent(
                                    KEEP_START_ON_BOOT_WIRELESS, true
                                )
                            }

                            showSecureSettingsPermissionDialog()
                            return@OnPreferenceChangeListener false
                        }
                        // Disable the root option because of mutual exclusivity
                        startOnBootPreference.isChecked = false
                        savePreference(KEEP_START_ON_BOOT, false)
                    }
                    toggleBootComponent(
                        KEEP_START_ON_BOOT_WIRELESS, newValue
                    )
                } else false
            }


        adbRoot.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) true
            else false
        }

        languagePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is String) {
                    val locale: Locale = if ("SYSTEM" == newValue) {
                        LocaleDelegate.systemLocale
                    } else {
                        Locale.forLanguageTag(newValue)
                    }
                    LocaleDelegate.defaultLocale = locale
                    activity?.recreate()
                }
                true
            }

        setupLocalePreference()

        nightModePreference.value = ShizukuSettings.getNightMode()
        nightModePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        activity?.recreate()
                    }
                }
                true
            }
        if (ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
            blackNightThemePreference.isChecked = ThemeHelper.isBlackNightTheme(context)
            blackNightThemePreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, _: Any? ->
                    if (ResourceUtils.isNightMode(context.resources.configuration)) {
                        activity?.recreate()
                    }
                    true
                }
        } else {
            blackNightThemePreference.isVisible = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            useSystemColorPreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                    if (value is Boolean) {
                        if (ThemeHelper.isUsingSystemColor() != value) {
                            activity?.recreate()
                        }
                    }
                    true
                }
        } else {
            useSystemColorPreference.isVisible = false
        }

        translationPreference.summary = context.getString(
            R.string.settings_translation_summary, context.getString(R.string.app_name)
        )
        translationPreference.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
            true
        }

        val contributors = context.getString(R.string.translation_contributors).toHtml().toString()
        if (contributors.isNotBlank()) {
            translationContributorsPreference.summary = contributors
        } else {
            translationContributorsPreference.isVisible = false
        }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView =
            super.onCreateRecyclerView(inflater, parent, savedInstanceState) as BorderRecyclerView
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        val lp = recyclerView.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.rightMargin =
                recyclerView.context.resources.getDimension(R.dimen.rd_activity_horizontal_margin)
                    .toInt()
            lp.leftMargin = lp.rightMargin
        }

        return recyclerView
    }

    override fun onResume() {
        super.onResume()

        tryToGrantSecureSettingsPermission()

        updatePreferenceStates()
    }

    private fun setupLocalePreference() {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

        languagePreference.entries = displayLocaleTags
        languagePreference.entryValues = localeTags

        val currentLocaleTag = languagePreference.value
        val currentLocaleIndex = localeTags.indexOf(currentLocaleTag)
        val currentLocale = ShizukuSettings.getLocale()
        val localizedLocales = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                localizedLocales.add(getString(R.string.follow_system))
                continue
            }

            val locale = Locale.forLanguageTag(displayLocale.toString())
            val localeName = if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(locale)
            else locale.getDisplayName(locale)

            val localizedLocaleName =
                if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(currentLocale)
                else locale.getDisplayName(currentLocale)

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName</small>".toHtml()
                } else {
                    localizedLocaleName
                }
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.summary = when {
            TextUtils.isEmpty(currentLocaleTag) || "SYSTEM" == currentLocaleTag -> {
                getString(R.string.follow_system)
            }

            currentLocaleIndex != -1 -> {
                val localizedLocale = localizedLocales[currentLocaleIndex]
                val newLineIndex = localizedLocale.indexOf('\n')
                if (newLineIndex == -1) {
                    localizedLocale.toString()
                } else {
                    localizedLocale.subSequence(0, newLineIndex).toString()
                }
            }

            else -> {
                ""
            }
        }
    }

    private fun savePreference(key: String, value: Boolean) {
        ShizukuSettings.getPreferences().edit() { putBoolean(key, value) }
    }

    private fun updatePreferenceStates() {
        val pm = requireContext().packageManager
        val isComponentEnabled = pm.isComponentEnabled(bootComponentName) == true
        val isWirelessBootEnabled =
            ShizukuSettings.getPreferences().getBoolean(KEEP_START_ON_BOOT_WIRELESS, false)
        val hasPermission = hasSecureSettingsPermission()

        startOnBootPreference.isChecked = isComponentEnabled && !isWirelessBootEnabled
        startOnBootWirelessPreference.isChecked =
            isComponentEnabled && isWirelessBootEnabled && hasPermission

        if (isWirelessBootEnabled && (!isComponentEnabled || !hasPermission)) {
            startOnBootWirelessPreference.isChecked = false
            savePreference(KEEP_START_ON_BOOT_WIRELESS, false)
        }
    }

    private fun toggleBootComponent(
        key: String, enabled: Boolean
    ): Boolean {
        savePreference(key, enabled)

        try {
            val pm = context?.packageManager
            pm?.setComponentEnabled(bootComponentName, enabled)

            val isEnabled = pm?.isComponentEnabled(bootComponentName) == enabled
            if (!isEnabled) {
                Log.e(
                    TAG, "Failed to verify component state change: $bootComponentName to $enabled"
                )
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting component state: $bootComponentName", e)
            Toast.makeText(
                requireContext(), R.string.wireless_boot_component_error, Toast.LENGTH_SHORT
            ).show()
            return false
        }

        return true
    }

    private fun showSecureSettingsPermissionDialog() {
        val context = requireContext()

        MaterialAlertDialogBuilder(context).setTitle(R.string.permission_required).setMessage(
            HtmlCompat.fromHtml(
                """
                <p>${getString(R.string.permission_write_secure_settings_required)}</p>
                <h3>Warning</h3>
                <p><tt>WRITE_SECURE_SETTINGS</tt> is a very sensitive permission and enable it only if you know what you're doing as the permission allows the application to read or write the secure system settings.</p>
                """.trimIndent()
            )
        ).setPositiveButton(R.string.permission_grant_automatically) { _, _ ->
            ShizukuSettings.getPreferences().edit() {
                putBoolean(
                    PENDING_SECURE_SETTINGS_GRANT, true
                )
            }

            if (!Shizuku.pingBinder()) {
                Toast.makeText(
                    context, R.string.start_shizuku_first, Toast.LENGTH_LONG
                ).show()

                // Return to main screen
                activity?.onBackPressedDispatcher?.onBackPressed()
                return@setPositiveButton
            }

            try {
                grantSecureSettingsWithShizuku()

                if (hasSecureSettingsPermission()) {
                    ShizukuSettings.getPreferences().edit() {
                        putBoolean(
                            PENDING_SECURE_SETTINGS_GRANT, false
                        )
                    }

                    Toast.makeText(
                        context, R.string.permission_granted, Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context, R.string.permission_grant_failed, Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to grant permission", e)
                Toast.makeText(
                    context, R.string.permission_grant_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }.setNegativeButton(R.string.permission_grant_manually) { _, _ ->
            showAdbInstructionsDialog()
        }.setNeutralButton(android.R.string.cancel, null).show()
    }

    private fun showAdbInstructionsDialog() {
        val context = requireContext()
        val command =
            "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS"

        MaterialAlertDialogBuilder(context).setTitle(R.string.home_adb_button_view_command)
            .setMessage(
                HtmlCompat.fromHtml(
                    getString(R.string.home_adb_dialog_view_command_message, command)
                )
            ).setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                ClipboardUtils.put(context, command)
                Toast.makeText(
                    context,
                    getString(R.string.toast_copied_to_clipboard, command),
                    Toast.LENGTH_SHORT
                ).show()
            }.setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, command)
                }
                context.startActivity(
                    Intent.createChooser(
                        intent, getString(R.string.home_adb_dialog_view_command_button_send)
                    )
                )
            }.show()
    }

    private fun hasSecureSettingsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun tryToGrantSecureSettingsPermission(): Boolean {
        if (hasSecureSettingsPermission()) {
            return true
        }

        val pendingGrant =
            ShizukuSettings.getPreferences().getBoolean(PENDING_SECURE_SETTINGS_GRANT, false)
        if (pendingGrant && Shizuku.pingBinder()) {
            try {
                val success = kotlin.runCatching {
                    grantSecureSettingsWithShizuku()
                    hasSecureSettingsPermission()
                }.onFailure { e ->
                    Log.e(TAG, "Error auto-granting permission", e)
                }.getOrDefault(false)

                if (success) {
                    ShizukuSettings.getPreferences().edit {
                        putBoolean(PENDING_SECURE_SETTINGS_GRANT, false)
                    }

                    activity?.runOnUiThread {
                        Toast.makeText(
                            requireContext(), R.string.permission_granted, Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                } else {
                    Log.w(TAG, "Auto-grant attempt finished, but permission still not granted.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to grant permission", e)
            }
        }
        return false
    }

    private fun grantSecureSettingsWithShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku service not available")
                throw IllegalStateException("Shizuku service not available for granting permission")
            }

            val uid = Process.myUid()
            val userHandle = UserHandle.getUserHandleForUid(uid)
            val userId = try {
                userHandle.toString().substringAfter("{").substringBefore("}").toInt()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get user ID from UserHandle", e)
                throw IllegalStateException("Failed to parse user ID", e)
            }

            Log.i(TAG, "Attempting to grant WRITE_SECURE_SETTINGS for user ID: $userId (UID: $uid)")

            ShizukuSystemApis.grantRuntimePermission(
                BuildConfig.APPLICATION_ID, Manifest.permission.WRITE_SECURE_SETTINGS, userId
            )

            Thread.sleep(200)
            
            Log.i(TAG, "Requested WRITE_SECURE_SETTINGS grant via Shizuku for user $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error granting permission via Shizuku", e)
            throw e
        }
    }
}
