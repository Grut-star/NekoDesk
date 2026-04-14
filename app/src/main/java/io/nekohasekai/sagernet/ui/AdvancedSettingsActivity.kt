package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.preference.SwitchPreferenceCompat
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.nekohasekai.sagernet.database.SagerDatabase
import android.util.Base64
import android.widget.Toast


class AdvancedSettingsActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.advanced_settings_entry)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, AdvancedSettingsFragment())
                .commit()
        }
    }

    // Обработка нажатия на стрелочку "Назад"
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// Фрагмент, который загружает сам XML
class AdvancedSettingsFragment : PreferenceFragmentCompat() {
    private var firewallSwitch: SwitchPreferenceCompat? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        addPreferencesFromResource(R.xml.advanced_preferences)

        firewallSwitch = findPreference("firewall_enabled")

        firewallSwitch?.setOnPreferenceChangeListener { _, newValue ->
            val isEnabled = newValue as Boolean

            // Если пытаемся включить, но прав нет
            if (isEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${requireContext().packageName}".toUri()
                )
                startActivity(intent)

                // Возвращаем false, чтобы тумблер визуально не переключился в "Вкл",
                // пока пользователь реально не даст права
                return@setOnPreferenceChangeListener false
            }
            true
        }
        // ---  КОД ДЛЯ ТЕСТОВОЙ КНОПКИ ---
        val testPopupPref = findPreference<androidx.preference.Preference>("firewall_test_popup")
        testPopupPref?.setOnPreferenceClickListener {
            // Вызываем наш менеджер с фейковыми данными
            FirewallOverlayManager.showPopup(
                context = requireContext(),
                uid = requireContext().applicationInfo.uid,
                appName = "nekodesk",
                domain = "example.com",
                onAllowOnce = {
                    // Здесь могла быть логика разрешения
                },
                onDenyOnce = {
                    // Здесь могла быть логика запрета
                },
                onCreateRule = {

                }
            )
            // Возвращаем true, показывая, что нажатие обработано
            true
        }
        // --- ЛОГИКА ЭКСПОРТА НАСТРОЕК И ПРАВИЛ ---
        val exportPref = findPreference<androidx.preference.Preference>("firewall_whitelist")
        exportPref?.setOnPreferenceClickListener {
            exportSettingsAndRules()
            true
        }
    }

    private fun exportSettingsAndRules() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val rootObj = JSONObject()

                // 1. Собираем глобальные настройки
                val settingsObj = JSONObject()
                settingsObj.put("mtu", DataStore.mtu)
                settingsObj.put(
                    "use_physical_mtu",
                    DataStore.configurationStore.getBoolean("use_physical_mtu", true)
                )
                settingsObj.put(
                    "strict_killswitch",
                    DataStore.configurationStore.getBoolean("strict_killswitch", false)
                )
                settingsObj.put(
                    "firewall_enabled",
                    DataStore.configurationStore.getBoolean("firewall_enabled", false)
                )
                rootObj.put("settings", settingsObj)

                // 2. Собираем активные правила брандмауэра и маршрутизации
                val rulesArray = JSONArray()
                val rules = SagerDatabase.rulesDao.enabledRules()
                for (rule in rules) {
                    val ruleObj = JSONObject()
                    ruleObj.put("name", rule.name)
                    ruleObj.put("outbound", rule.outbound)
                    if (!rule.domains.isNullOrEmpty()) ruleObj.put("domains", rule.domains)
                    if (!rule.packages.isNullOrEmpty()) {
                        val pkgArray = JSONArray()
                        rule.packages!!.forEach { pkgArray.put(it) }
                        ruleObj.put("packages", pkgArray)
                    }
                    rulesArray.put(ruleObj)
                }
                rootObj.put("rules", rulesArray)

                // 3. Сжимаем (GZIP) и кодируем (Base64)
                val jsonString = rootObj.toString()
                val byteStream = ByteArrayOutputStream()
                GZIPOutputStream(byteStream).use { it.write(jsonString.toByteArray(Charsets.UTF_8)) }
                val base64 = Base64.encodeToString(
                    byteStream.toByteArray(),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )

                // Формируем ссылку (Используем https, чтобы она была кликабельной во всех чатах)
                val link = "https://nekodesk.app/import?payload=$base64"

                // 4. Открываем системное меню "Поделиться"
                withContext(Dispatchers.Main) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_TEXT, "Мои настройки и правила NekoDesk:\n\n$link")
                        type = "text/plain"
                    }
                    startActivity(Intent.createChooser(sendIntent, "Поделиться настройками"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Ошибка экспорта: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Когда возвращаемся из настроек Android, проверяем, дали ли нам права
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (firewallSwitch?.isChecked == true && !Settings.canDrawOverlays(requireContext())) {
                // Если пользователь каким-то образом отозвал права, выключаем тумблер
                firewallSwitch?.isChecked = false
            }
        }
    }
}