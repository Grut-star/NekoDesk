package io.nekohasekai.sagernet.bg

import java.util.concurrent.ConcurrentHashMap

data class BlockedIpRequest(
    val uid: Int,
    val appName: String,
    val ip: String,
    var timestamp: Long,
    var count: Int
)

object FirewallIpHistoryManager {
    private const val MAX_HISTORY_SIZE = 1000
    // Ключ: "uid:ip" для группировки одинаковых запросов
    private val history = ConcurrentHashMap<String, BlockedIpRequest>()

    fun addRecord(uid: Int, appName: String, ip: String) {
        val key = "$uid:$ip"
        val existing = history[key]
        if (existing != null) {
            existing.timestamp = System.currentTimeMillis() // Обновляем время
            existing.count += 1 // Увеличиваем счетчик попыток
        } else {
            // Если мы достигли лимита, удаляем самую старую запись перед добавлением новой
            if (history.size >= MAX_HISTORY_SIZE) {
                // Ищем ключ с минимальным timestamp (самый старый)
                val oldestKey = history.entries.minByOrNull { it.value.timestamp }?.key
                if (oldestKey != null) {
                    history.remove(oldestKey)
                }
            }
            history[key] = BlockedIpRequest(uid, appName, ip, System.currentTimeMillis(), 1)
        }
    }

    // Возвращает список, отсортированный от новых к старым
    fun getHistory(): List<BlockedIpRequest> {
        return history.values.sortedByDescending { it.timestamp }
    }

    fun removeRecord(uid: Int, ip: String) {
        history.remove("$uid:$ip")
    }

    fun clearAll() {
        history.clear()
    }
}