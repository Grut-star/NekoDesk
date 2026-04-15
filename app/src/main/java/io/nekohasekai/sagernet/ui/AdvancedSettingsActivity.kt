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
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AdguardCompiler
import java.io.File
import java.io.FileOutputStream
import java.net.URL


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

        // --- ИСТОРИЯ ЗАБЛОКИРОВАННЫХ ПРЯМЫХ IP ---
        val ipHistoryPref = findPreference<androidx.preference.Preference>("firewall_ip_history")
        ipHistoryPref?.setOnPreferenceClickListener {
            // Открываем нашу новую Activity с историей
            startActivity(Intent(requireContext(), BlockedIpHistoryActivity::class.java))
            true
        }

        // --- УПРАВЛЕНИЕ ФИЛЬТРАМИ ADGUARD ---
        setupMultiChoiceDialog("adg_filters_general", R.array.adg_general_names, R.array.adg_general_urls)
        setupMultiChoiceDialog("adg_filters_security", R.array.adg_security_names, R.array.adg_security_urls)
        setupMultiChoiceDialog("adg_filters_regional", R.array.adg_regional_names, R.array.adg_regional_urls)

        // Диалог для кастомных ссылок
        val customUrlsPref = findPreference<androidx.preference.Preference>("adg_custom_urls")
        customUrlsPref?.setOnPreferenceClickListener {
            val input = EditText(requireContext())
            input.setPadding(50, 40, 50, 40)
            input.hint = "https://example.com/filter.txt\nhttps://..."

            // Загружаем то, что было сохранено ранее
            val savedValue = DataStore.configurationStore.getString("adg_custom_urls", "")
            input.setText(savedValue)

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Свои ссылки на фильтры")
                .setMessage("Вставьте прямые ссылки (каждая с новой строки):")
                .setView(input)
                .setPositiveButton("Сохранить") { _, _ ->
                    // Сохраняем как обычную строку
                    DataStore.configurationStore.putString("adg_custom_urls", input.text.toString().trim())
                }
                .setNegativeButton("Отмена", null)
                .show()
            true
        }

        val adguardUpdatePref = findPreference<androidx.preference.Preference>("adg_update_button")
        adguardUpdatePref?.setOnPreferenceClickListener {
            updateAdguardFilters()
            true
        }

        val adguardClearPref = findPreference<androidx.preference.Preference>("adg_clear_button")
        adguardClearPref?.setOnPreferenceClickListener {
            clearAdguardFilters()
            true
        }

        // --- ЛОГИКА ЭКСПОРТА НАСТРОЕК И ПРАВИЛ ---
        val exportPref = findPreference<androidx.preference.Preference>("firewall_export")
        exportPref?.setOnPreferenceClickListener {
            showExportOptionsDialog() // Вызываем диалог вместо прямого экспорта
            true
        }
    }

    // ==========================================
    // ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ
    // ==========================================
    private fun setupMultiChoiceDialog(key: String, namesArrayId: Int, urlsArrayId: Int) {
        val pref = findPreference<androidx.preference.Preference>(key)
        pref?.setOnPreferenceClickListener {
            val names = resources.getStringArray(namesArrayId)
            val urls = resources.getStringArray(urlsArrayId)

            // Загружаем из БД строку "url1,url2" и разбиваем её в список
            val savedString = DataStore.configurationStore.getString(key, "") ?: ""
            val selectedUrls = savedString.split(",").filter { it.isNotBlank() }.toMutableSet()

            // Отмечаем галочками то, что было выбрано
            val checkedItems = BooleanArray(names.size) { i -> selectedUrls.contains(urls[i]) }

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(pref.title)
                .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                    if (isChecked) {
                        selectedUrls.add(urls[which])
                    } else {
                        selectedUrls.remove(urls[which])
                    }
                }
                .setPositiveButton("Сохранить") { _, _ ->
                    // Склеиваем обратно в строку через запятую и сохраняем
                    DataStore.configurationStore.putString(key, selectedUrls.joinToString(","))
                }
                .setNegativeButton("Отмена", null)
                .show()
            true
        }
    }

    // ==========================================
    // ЛОГИКА ОБНОВЛЕНИЯ ФИЛЬТРОВ
    // ==========================================
    private fun updateAdguardFilters() {
        val urlsToDownload = mutableSetOf<String>()
        val prefs = DataStore.configurationStore

        // 1. Достаем строки из БД
        val general = prefs.getString("adg_filters_general", "") ?: ""
        if (general.isNotBlank()) urlsToDownload.addAll(general.split(","))

        val security = prefs.getString("adg_filters_security", "") ?: ""
        if (security.isNotBlank()) urlsToDownload.addAll(security.split(","))

        val regional = prefs.getString("adg_filters_regional", "") ?: ""
        if (regional.isNotBlank()) urlsToDownload.addAll(regional.split(","))

        // 2. Добавляем кастомные ссылки
        val customUrls = prefs.getString("adg_custom_urls", "") ?: ""
        customUrls.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                urlsToDownload.add(trimmed)
            }
        }

        if (urlsToDownload.isEmpty()) {
            Toast.makeText(requireContext(), "Выберите хотя бы один фильтр!", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(requireContext(), "⏳ Начинаем скачивание (${urlsToDownload.size} списков). Не закрывайте экран...", Toast.LENGTH_LONG).show()

        // Сохраняем контекст, чтобы он не потерялся, если фрагмент свернется
        val appContext = requireContext().applicationContext

        GlobalScope.launch(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            var successCount = 0
            val mergedFile = File(appContext.cacheDir, "merged_adguard.txt")

            try {
                if (mergedFile.exists()) mergedFile.delete()

                // Открываем файл для дозаписи
                FileOutputStream(mergedFile, true).use { output ->
                    for (url in urlsToDownload) {
                        try {
                            // ИСПОЛЬЗУЕМ ПРОДВИНУТОЕ ПОДКЛЮЧЕНИЕ
                            val connection = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                            connection.connectTimeout = 15000 // Ждем максимум 15 сек на подключение
                            connection.readTimeout = 20000    // Ждем максимум 20 сек на скачивание
                            connection.instanceFollowRedirects = true // Обязательно для GitHub/GitLab

                            // Притворяемся браузером, некоторые списки блокируют ботов
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

                            if (connection.responseCode in 200..299) {
                                connection.inputStream.use { input ->
                                    input.copyTo(output)
                                }
                                output.write("\n".toByteArray())
                                successCount++
                            } else {
                                errors.add("HTTP ${connection.responseCode}: $url")
                            }
                        } catch (e: Exception) {
                            errors.add("${e.javaClass.simpleName}: $url")
                        }
                    }
                }

                // ПРОВЕРКА: Скачалось ли хоть что-нибудь?
                if (successCount == 0) {
                    mergedFile.delete()
                    withContext(Dispatchers.Main) {
                        showDownloadResultDialog(
                            "❌ Ошибка обновления",
                            "Не удалось скачать ни один из ${urlsToDownload.size} списков.\n\nПричины:\n${errors.joinToString("\n")}"
                        )
                    }
                    return@launch
                }

                // 3. Компилируем общий файл
                AdguardCompiler.compileAdguardToSrs(appContext, mergedFile)
                mergedFile.delete()

                withContext(Dispatchers.Main) {
                    // Формируем красивый отчет
                    val msg = if (errors.isEmpty()) {
                        "✅ Все $successCount списков успешно скачаны и скомпилированы!"
                    } else {
                        "⚠️ Скомпилировано списков: $successCount.\n\nНе удалось скачать:\n${errors.joinToString("\n")}"
                    }

                    showDownloadResultDialog("Обновление завершено", msg)

                    if (DataStore.serviceState.canStop) {
                        SagerNet.reloadService()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showDownloadResultDialog("❌ Критическая ошибка", e.message ?: "Неизвестная ошибка")
                }
            }
        }
    }

    // Вспомогательный метод для показа отчета на экране
    private fun showDownloadResultDialog(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    private fun clearAdguardFilters() {
        val outputDir = File(requireContext().filesDir, "adguard_rules")
        var count = 0
        if (outputDir.exists()) {
            outputDir.listFiles()?.forEach {
                it.delete()
                count++
            }
        }
        Toast.makeText(requireContext(), "🗑 Удалено файлов правил: $count", Toast.LENGTH_SHORT).show()

        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        }
    }

    // ==========================================
    // ЛОГИКА ЭКСПОРТА
    // ==========================================

    private fun showExportOptionsDialog() {
        val options = arrayOf(
            "📦 Всё вместе (Настройки и маршруты)",
            "⚙️ Только настройки",
            "🔀 Только правила маршрутизации"
        )

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Что экспортировать?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportData(includeSettings = true, includeRules = true)
                    1 -> exportData(includeSettings = true, includeRules = false)
                    2 -> exportData(includeSettings = false, includeRules = true)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun exportData(includeSettings: Boolean, includeRules: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val rootObj = JSONObject()
                if (includeSettings) {
                    val settingsObj = JSONObject()

                    // --- БАЗОВЫЕ НАСТРОЙКИ ---
                    settingsObj.put("mtu", DataStore.mtu)
                    settingsObj.put("use_physical_mtu", DataStore.usePhysicalMtu)
                    settingsObj.put("strict_killswitch", DataStore.strictKillswitch)
                    settingsObj.put("firewall_enabled", DataStore.firewallEnabled)

                    // --- ФИЛЬТРАЦИЯ ПРИЛОЖЕНИЙ (Per-App Proxy) ---
                    // proxyApps = Включен ли режим фильтрации
                    // bypass = Режим работы: true (Обходить VPN), false (Проксировать через VPN)
                    // individual = Строка со списком выбранных пакетов (com.android.chrome\ncom.telegram.messenger)
                    settingsObj.put("proxy_apps", DataStore.proxyApps)
                    settingsObj.put("bypass_mode", DataStore.bypass)
                    settingsObj.put("individual", DataStore.individual)

                    // --- НАСТРОЙКИ DNS ---
                    settingsObj.put("remote_dns", DataStore.remoteDns)
                    settingsObj.put("direct_dns", DataStore.directDns)
                    settingsObj.put("enable_dns_routing", DataStore.enableDnsRouting)
                    settingsObj.put("enable_fakedns", DataStore.enableFakeDns)

                    // --- МАРШРУТИЗАЦИЯ И ЯДРО ---
                    settingsObj.put("ipv6_mode", DataStore.ipv6Mode)
                    settingsObj.put("traffic_sniffing", DataStore.trafficSniffing)
                    settingsObj.put("tun_implementation", DataStore.tunImplementation)
                    settingsObj.put("bypass_lan", DataStore.bypassLan)
                    settingsObj.put("bypass_lan_in_core", DataStore.bypassLanInCore)
                    settingsObj.put("allow_access", DataStore.allowAccess)

                    // --- ЛОКАЛЬНЫЕ ПОРТЫ И ПРОЧЕЕ ---
                    settingsObj.put("mixed_port", DataStore.mixedPort)
                    settingsObj.put("keepLocalPortInVpn", DataStore.keepLocalPortInVpn)
                    settingsObj.put("service_mode", DataStore.serviceMode) // VPN или Proxy Only

                    rootObj.put("settings", settingsObj)
                }

                if (includeRules) {
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
                }

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

                // Динамический текст для окна "Поделиться"
                val shareMessage = when {
                    includeSettings && includeRules -> "Мои настройки и правила маршрутизации NekoDesk:\n\n$link"
                    includeSettings -> "Мои настройки NekoDesk:\n\n$link"
                    else -> "Мои правила маршрутизации NekoDesk:\n\n$link"
                }

                // 4. Открываем системное меню "Поделиться"
                withContext(Dispatchers.Main) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_TEXT, shareMessage)
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