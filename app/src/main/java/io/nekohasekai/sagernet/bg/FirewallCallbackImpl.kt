package io.nekohasekai.sagernet.bg

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.ui.FirewallOverlayManager
import libcore.FirewallCallback
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
class FirewallCallbackImpl(private val context: Context) : FirewallCallback {

    // Кэш 1: Точечные решения (UID:Домен -> Разрешено ли)
    // Кэш для решений: ключ — "uid:domain", значение — разрешено ли (true/false)
    private val sessionDomainCache = ConcurrentHashMap<String, Boolean>()

    // Кэш 2: Глобальные решения для ВСЕГО приложения (UID -> Разрешено ли)
    private val sessionAppCache = ConcurrentHashMap<Int, Boolean>()


    // Напоминалка: gomobile генерирует uid как Long, а Android использует Int,
    // поэтому нужен привод типов

    init {
        // ПРЕДЗАГРУЗКА: При старте VPN загружаем все сохраненные правила из БД прямо в наш быстрый кэш.
        // Это позволяет обходить всплывающие окна мгновенно, не дергая ядро.
        GlobalScope.launch(Dispatchers.IO) {
            try {
                PackageCache.awaitLoadSync() // Убеждаемся, что список установленных пакетов загружен
                val rules = SagerDatabase.rulesDao.enabledRules()

                for (rule in rules) {
                    if (rule.name.startsWith("FW:")) { // Берем только правила нашего брандмауэра
                        val isAllow = rule.outbound == 0L // 0L = Proxy (разрешить), -2L = Block (запретить)
                        val packages = rule.packages

                        if (!packages.isNullOrEmpty()) {
                            for (pkgName in packages) { // Проходим по всем пакетам в правиле, а не только по первому
                                val uid = PackageCache[pkgName]
                                if (uid != null && uid >= 1000) {
                                    val domainRule = rule.domains

                                    if (domainRule.isNullOrEmpty()) {
                                        // Правило применялось на ВСЁ приложение
                                        sessionAppCache[uid] = isAllow
                                    } else {
                                        // Обязательно разбиваем домены по переносу строки!
                                        domainRule.split("\n").forEach { line ->
                                            val cleanDomain = line.trim().removePrefix("full:")
                                            if (cleanDomain.isNotEmpty()) {
                                                sessionDomainCache["$uid:$cleanDomain"] = isAllow
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Logs.i("[FIREWALL] \uD83E\uDDE0 Кэш брандмауэра предзагружен из БД (${rules.size} правил)")
            } catch (e: Exception) {
                Logs.e("[FIREWALL] ⚠️ Ошибка загрузки кэша из БД: ${e.message}")
            }
        }
    }

    override fun askDnsPermission(uid: Long, domain: String?): Boolean {
        if (domain == null) return true // Защита от пустых вызовов

        val realUid = uid.toInt()

        // Быстрая проверка: если мы УЖЕ разрешили/запретили всё приложение
        sessionAppCache[realUid]?.let { return it }

        // Быстрая проверка: если есть правило на этот конкретный домен
        val cacheKey = "$realUid:$domain"
        sessionDomainCache[cacheKey]?.let { return it }
        val appName = getAppNameByUid(realUid)

        // ЭТО ПОЯВИТСЯ ВО ВКЛАДКЕ ЛОГОВ ПРИЛОЖЕНИЯ
        Logs.i("[FIREWALL] 🚦 Запрос DNS: $domain от $appName (UID: $realUid)")

        // CountDownLatch(1) — это наш "замок". Он остановит этот фоновый поток,
        // пока мы не вызовем countDown() внутри обработчиков кнопок.
        val latch = CountDownLatch(1)
        var isAllowed = false

        // Переключаемся в главный (UI) поток, чтобы нарисовать окно
        Handler(Looper.getMainLooper()).post {
            try {
                FirewallOverlayManager.showPopup(
                    context = context,
                    uid = realUid,
                    appName = appName,
                    domain = domain,
                    onAllowOnce = {
                        Logs.i("[FIREWALL] ✅ Разрешено: $domain")
                        isAllowed = true
                        latch.countDown() // Открываем замок!
                    },
                    onDenyOnce = {
                        Logs.i("[FIREWALL] ❌ Запрещено: $domain")
                        isAllowed = false
                        latch.countDown() // Открываем замок!
                    },
                    onCreateRule = { ruleType ->
                        // ruleType: 1 - Разрешить домен, 2 - Запретить домен, 3 - Разрешить всё приложению, 4 - Запретить всё
                        Logs.i("[FIREWALL] \uD83D\uDCDD Создано правило типа $ruleType для $domain / $appName")

                        val allow = (ruleType == 1 || ruleType == 3)
                        isAllowed = allow

                        // МГНОВЕННОЕ ПРИМЕНЕНИЕ В ОЗУ (Чтобы окно не вылезло на следующий же запрос)
                        if (ruleType == 3 || ruleType == 4) {
                            sessionAppCache[realUid] = allow
                            sessionDomainCache.keys.filter { it.startsWith("$realUid:") }.forEach {
                                sessionDomainCache.remove(it)
                            }
                            Logs.i("[FIREWALL] \uD83D\uDCF1 Правило кэшировано для ВСЕГО приложения: $appName")
                        } else {
                            sessionDomainCache[cacheKey] = allow
                            Logs.i("[FIREWALL] \uD83C\uDF10 Правило кэшировано для домена: $domain")
                        }
                        // СОХРАНЯЕМ ПРАВИЛО В БАЗУ ДАННЫХ
                        saveRuleToDatabase(ruleType, appName, domain, realUid, allow)

                        latch.countDown()
                    }
                )
            }
            catch (e: Exception) {
                // Если окно крашнулось (например, нет прав), мы ОБЯЗАНЫ открыть замок
                Logs.e("[FIREWALL] 💥 Ошибка показа окна: ${e.message}")
                isAllowed = true // Пропускаем трафик, чтобы не сломать интернет
                latch.countDown()
            }
        }

        // БЛОКИРУЕМ ПОТОК И ЖДЕМ
        // Поток будет спать здесь, пока пользователь не нажмет кнопку.
        // Добавим таймаут в 15 секунд. Если юзер отошел от телефона, мы не можем
        // вечно держать ядро заблокированным.
        val answered = latch.await(15, TimeUnit.SECONDS)

        if (!answered) {
            Logs.i("[FIREWALL] ⏰ Таймаут ответа пользователя для $domain. Блокируем временно.")
            // Если вышло время (15 секунд), а пользователь ничего не нажал:
            // Скрываем окно и по умолчанию ЗАПРЕЩАЕМ запрос (нефиг ходить куда ни попадя)
            Handler(Looper.getMainLooper()).post { FirewallOverlayManager.hidePopup() }
            return false
        }

        // Возвращаем результат (true или false) обратно в ядро Go
        return isAllowed
    }

    override fun notifyDirectIpBlocked(uid: Long, ip: String?) {
        if (ip == null) return
        val realUid = uid.toInt()
        val appName = getAppNameByUid(realUid)
        // Добавляем запись в нашу историю
        FirewallIpHistoryManager.addRecord(realUid, appName, ip)

        Logs.i("[FIREWALL] 🛑 Блок прямого IP: $ip от $appName")
    }

    /**
     * Вспомогательная функция: вытаскиваем имя приложения по его UID
     */
    private fun getAppNameByUid(uid: Int): String {
        // У Android System и Root процессов UID < 10000
        if (uid < 10000) return "Система Android (UID: $uid)"

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)

        if (!packages.isNullOrEmpty()) {
            try {
                // Берем первый пакет с этим UID
                val appInfo = pm.getApplicationInfo(packages[0], 0)
                return pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                // Игнорируем ошибку, если пакет не найден, чтобы не вылетать
            }
        }
        return "Неизвестное приложение ($uid)"
    }

    /**
     * Создает реальное правило в базе данных SagerNet
     */
    private fun saveRuleToDatabase(ruleType: Int, appName: String, domain: String, uid: Int, allow: Boolean) {
        // Мы используем GlobalScope, чтобы не блокировать текущий поток (работа с БД должна быть асинхронной)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Нам нужно получить имя пакета по UID, так как SagerNet хранит пакеты, а не UID
                val pm = context.packageManager
                val packages = pm.getPackagesForUid(uid)
                val packageName = if (!packages.isNullOrEmpty()) packages[0] else ""

                val targetOutbound = if (allow) 0L else -2L // 0L = Proxy, -2L = Block
                val oppositeOutbound = if (allow) -2L else 0L // Нам нужно знать противоположное действие
                val dao = SagerDatabase.rulesDao

                // Ищем все существующие правила
                val allRules = dao.allRules()
                if (ruleType == 3 || ruleType == 4) {
                    // Ищем ПРОТИВОРЕЧАЩИЕ глобальные правила и вычищаем из них это приложение
                    allRules.filter { it.name.startsWith("FW:") && it.domains.isBlank() && it.outbound == oppositeOutbound }
                        .forEach { rule ->
                            val currentPkgs = rule.packages?.toMutableSet() ?: mutableSetOf()
                            if (currentPkgs.remove(packageName)) {
                                rule.packages = currentPkgs
                                // Если в правиле больше нет приложений, удаляем правило целиком, иначе обновляем
                                if (currentPkgs.isEmpty()) dao.deleteRule(rule) else dao.updateRule(rule)
                            }
                        }
                    // Добавляем приложение в НУЖНОЕ глобальное правило
                    val targetRule = allRules.firstOrNull {
                        it.name.startsWith("FW:") && it.domains.isBlank() && it.outbound == targetOutbound
                    }

                    if (targetRule != null) {
                        val currentPkgs = targetRule.packages?.toMutableSet() ?: mutableSetOf()
                        if (currentPkgs.add(packageName)) {
                            targetRule.packages = currentPkgs
                            dao.updateRule(targetRule)
                        }
                    } else{
                        // Создаем новое глобальное правило
                        val rule = RuleEntity().apply {
                            name = "FW: ${if(allow) "✔" else "❌"} ВСЁ ($appName)"
                            enabled = true
                            outbound = targetOutbound
                            this.packages = setOf(packageName)
                            userOrder = dao.nextOrder() ?: 0L
                        }
                        dao.createRule(rule)
                    }
                    Logs.i("[FIREWALL] 💾 Глобальное правило для $appName сохранено!")

                } else {
                    // РЕЖИМ "КОНКРЕТНЫЙ ДОМЕН"
                    // Ищем существующее правило для этого пакета с таким же действием
                    val newDomainEntry = "full:$domain"

                    // Удаляем этот домен из ПРОТИВОРЕЧАЩЕГО правила (например, удаляем из Block, чтобы поместить в Allow)
                    allRules.filter {
                        it.name.startsWith("FW:") &&
                                (it.packages?.contains(packageName) == true) &&
                                it.outbound == oppositeOutbound &&
                                it.domains.isNotBlank()
                    }.forEach { rule ->
                        val currentDomains = rule.domains.split("\n").toMutableList()
                        if (currentDomains.remove(newDomainEntry)) {
                            rule.domains = currentDomains.joinToString("\n")
                            if (rule.domains.isBlank()) {
                                dao.deleteRule(rule) // Доменов не осталось, удаляем мусорное правило
                            } else {
                                rule.name = "FW: ${if(!allow) "✔" else "❌"} ${currentDomains.size} доменов ($appName)"
                                dao.updateRule(rule)
                            }
                        }
                    }
                    // Добавляем домен в НУЖНОЕ правило
                    val existingRule = allRules.firstOrNull {
                        it.name.startsWith("FW:") &&
                                (it.packages?.contains(packageName) == true) &&
                                it.outbound == targetOutbound &&
                                it.domains.isNotBlank() // Ищем именно доменные правила
                    }


                    if (existingRule != null) {
                        // ПРАВИЛО НАЙДЕНО: Дописываем домен
                        val currentDomains = existingRule.domains.split("\n").toMutableList()

                        if (!currentDomains.contains(newDomainEntry)) {
                            currentDomains.add(newDomainEntry)
                            existingRule.domains = currentDomains.joinToString("\n")
                            // Обновляем количество доменов в названии (для красоты в UI)
                            existingRule.name = "FW: ${if(allow) "✔" else "❌"} ${currentDomains.size} доменов ($appName)"

                            dao.updateRule(existingRule)
                            Logs.i("[FIREWALL] 💾 Домен $domain добавлен в существующее правило для $appName!")
                        }
                    } else {
                        // ПРАВИЛА НЕТ: Создаем новое
                        val rule = RuleEntity().apply {
                            name = "FW: ${if(allow) "✔" else "❌"} 1 домен ($appName)"
                            enabled = true
                            outbound = targetOutbound
                            domains = newDomainEntry
                            this.packages = setOf(packageName)
                            userOrder = dao.nextOrder() ?: 0L
                        }
                        dao.createRule(rule)
                        Logs.i("[FIREWALL] 💾 Создано новое правило для $appName с доменом $domain!")
                    }
                }

            } catch (e: Exception) {
                Logs.e("[FIREWALL] 💥 Ошибка сохранения правила: ${e.message}")
            }
        }
    }
}