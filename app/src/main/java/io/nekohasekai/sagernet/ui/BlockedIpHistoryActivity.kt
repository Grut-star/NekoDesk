package io.nekohasekai.sagernet.ui

import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BlockedIpRequest
import io.nekohasekai.sagernet.bg.FirewallIpHistoryManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class BlockedIpHistoryActivity : ThemedActivity() { // Наследуемся от базового класса NekoBox/SagerNet

    private lateinit var listView: ListView
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Устанавливаем нашу новую разметку
        setContentView(R.layout.activity_blocked_ip_history)

        // Настраиваем Тулбар и кнопку "Назад"
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true) // Показываем стрелочку
        supportActionBar?.title = "История блокировок IP"

        // Находим наш список
        listView = findViewById(R.id.history_list_view)

        // Заполняем данными
        refreshList()

        // Обработка долгого нажатия
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            showActionDialog(item)
            true
        }
    }

    // Метод, который срабатывает при нажатии на стрелочку "Назад" в тулбаре
    override fun onSupportNavigateUp(): Boolean {
        finish() // Закрываем этот экран и возвращаемся в настройки
        return true
    }

    private fun refreshList() {
        val data = FirewallIpHistoryManager.getHistory()
        adapter = HistoryAdapter(this, data)
        listView.adapter = adapter
    }

    private fun showActionDialog(item: BlockedIpRequest) {
        val options = arrayOf(
            "✅ Разрешить только этот IP (${item.ip})",
            "🌐 Разрешить ВСЕ прямые IP для ${item.appName}",
            "❌ Удалить из истории"
        )

        AlertDialog.Builder(this)
            .setTitle("Действие для ${item.appName}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> allowSingleIp(item)
                    1 -> allowAllForApp(item)
                    2 -> {
                        FirewallIpHistoryManager.removeRecord(item.uid, item.ip)
                        refreshList()
                    }
                }
            }
            .show()
    }

    private fun allowSingleIp(item: BlockedIpRequest) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val packages = pm.getPackagesForUid(item.uid)
                val packageName = if (!packages.isNullOrEmpty()) packages[0] else return@launch

                val dao = SagerDatabase.rulesDao
                // Создаем правило только для конкретного IP
                val rule = RuleEntity().apply {
                    name = "FW: ✔ IP ${item.ip} (${item.appName})"
                    enabled = true
                    outbound = 0L // Proxy (Разрешить)
                    ip = item.ip // В SagerNet поле для IP называется ip
                    this.packages = setOf(packageName)
                    userOrder = dao.nextOrder() ?: 0L
                }
                dao.createRule(rule)

                // Удаляем из истории и обновляем UI
                FirewallIpHistoryManager.removeRecord(item.uid, item.ip)
                runOnUiThread { refreshList() }
                Logs.i("[FIREWALL] 💾 IP ${item.ip} разрешен для ${item.appName}")
            } catch (e: Exception) {
                Logs.e("Ошибка сохранения IP правила: ${e.message}")
            }
        }
    }

    private fun allowAllForApp(item: BlockedIpRequest) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val packages = pm.getPackagesForUid(item.uid)
                val packageName = if (!packages.isNullOrEmpty()) packages[0] else return@launch

                val dao = SagerDatabase.rulesDao
                // Создаем глобальное правило для приложения
                val rule = RuleEntity().apply {
                    name = "FW: ✔ ВСЁ (${item.appName})"
                    enabled = true
                    outbound = 0L
                    this.packages = setOf(packageName)
                    userOrder = dao.nextOrder() ?: 0L
                }
                dao.createRule(rule)

                // Очищаем ВСЕ записи этого приложения из истории
                val historyToClear = FirewallIpHistoryManager.getHistory().filter { it.uid == item.uid }
                historyToClear.forEach { FirewallIpHistoryManager.removeRecord(it.uid, it.ip) }

                runOnUiThread { refreshList() }
                Logs.i("[FIREWALL] 💾 Глобальное правило создано для ${item.appName}")
            } catch (e: Exception) {
                Logs.e("Ошибка сохранения глобального правила: ${e.message}")
            }
        }
    }

    // Простой адаптер для списка
    inner class HistoryAdapter(context: Context, objects: List<BlockedIpRequest>) :
        ArrayAdapter<BlockedIpRequest>(context, android.R.layout.simple_list_item_2, android.R.id.text1, objects) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val item = getItem(position)

            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)

            if (item != null) {
                text1.text = "${item.appName} (Блоков: ${item.count})"
                val timeString = DateUtils.getRelativeTimeSpanString(item.timestamp)
                text2.text = "IP: ${item.ip} • $timeString"
            }
            return view
        }
    }
}