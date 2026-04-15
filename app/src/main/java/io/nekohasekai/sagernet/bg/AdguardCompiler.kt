package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File

object AdguardCompiler {

    fun compileAdguardToSrs(context: Context, inputFile: File) {
        val blockDomains = mutableListOf<String>()
        val blockSuffixes = mutableListOf<String>()
        val blockRegex = mutableListOf<String>()

        val allowDomains = mutableListOf<String>()
        val allowSuffixes = mutableListOf<String>()
        val allowRegex = mutableListOf<String>()

        try {
            Logs.i("[ADGUARD] ⏳ Парсинг файла...")
            inputFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) return@forEachLine

                var isAllow = false
                var rule = trimmed

                if (rule.startsWith("@@")) {
                    isAllow = true
                    rule = rule.substring(2)
                }

                when {
                    rule.startsWith("||") && rule.endsWith("^") -> {
                        val suffix = rule.substring(2, rule.length - 1)
                        if (isAllow) allowSuffixes.add(suffix) else blockSuffixes.add(suffix)
                    }
                    rule.startsWith("/") && rule.endsWith("/") -> {
                        val regex = rule.substring(1, rule.length - 1)
                        if (isAllow) allowRegex.add(regex) else blockRegex.add(regex)
                    }
                    rule.contains(" ") -> {
                        val parts = rule.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val ip = parts[0]
                            val domain = parts[1]
                            if (ip == "0.0.0.0" || ip == "127.0.0.1") {
                                if (isAllow) allowDomains.add(domain) else blockDomains.add(domain)
                            }
                        }
                    }
                    else -> {
                        val cleanDomain = rule.removeSuffix("^")
                        if (isAllow) allowDomains.add(cleanDomain) else blockDomains.add(cleanDomain)
                    }
                }
            }

            Logs.i("[ADGUARD] 📝 Парсинг завершен. Блок=${blockDomains.size + blockSuffixes.size}")

            val outputDir = File(context.filesDir, "adguard_rules")
            if (!outputDir.exists()) outputDir.mkdirs()

            // СОХРАНЯЕМ В ВИДЕ .json НАПРЯМУЮ
            val finalBlockJson = File(outputDir, "adguard_block.json")
            val finalAllowJson = File(outputDir, "adguard_allow.json")

            saveToStreamJson(finalBlockJson, blockDomains, blockSuffixes, blockRegex)
            saveToStreamJson(finalAllowJson, allowDomains, allowSuffixes, allowRegex)

            Logs.i("[ADGUARD] ✅ Правила JSON успешно созданы!")

        } catch (e: Exception) {
            Logs.e("[ADGUARD] ❌ Критическая ошибка: ${e.message}")
        }
    }

    // Сверхбыстрый потоковый JSON-генератор (не потребляет оперативную память)
    private fun saveToStreamJson(outputFile: File, domains: List<String>, suffixes: List<String>, regex: List<String>) {
        outputFile.bufferedWriter().use { writer ->
            writer.write("{\n  \"version\": 1,\n  \"rules\": [\n")
            var isRuleAdded = false

            fun writeArray(key: String, list: List<String>) {
                if (list.isEmpty()) return
                if (isRuleAdded) writer.write(",\n")
                writer.write("    {\n      \"$key\": [\n")

                list.forEachIndexed { index, s ->
                    val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
                    if (index == list.lastIndex) {
                        writer.write("        \"$escaped\"\n")
                    } else {
                        writer.write("        \"$escaped\",\n")
                    }
                }
                writer.write("      ]\n    }")
                isRuleAdded = true
            }

            writeArray("domain", domains)
            writeArray("domain_suffix", suffixes)
            writeArray("domain_regex", regex)

            writer.write("\n  ]\n}")
        }
    }
}