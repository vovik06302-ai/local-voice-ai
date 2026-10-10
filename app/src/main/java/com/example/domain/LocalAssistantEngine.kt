package com.example.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class LocalAssistantEngine {

    suspend fun processOffline(query: String, history: List<Pair<String, String>>): String {
        val trimmed = query.trim()
        val lower = trimmed.lowercase(Locale("ru"))

        // Time / Date queries
        if (lower.contains("сколько время") || lower.contains("который час") || lower.contains("какое время")) {
            val time = SimpleDateFormat("HH:mm", Locale("ru")).format(Date())
            return "Сейчас $time."
        }
        if (lower.contains("какой день") || lower.contains("какое число") || lower.contains("какая дата") || lower.contains("сегодня день")) {
            val date = SimpleDateFormat("d MMMM yyyy г., EEEE", Locale("ru")).format(Date())
            return "Сегодня $date."
        }

        // Greetings & Introductions
        if (lower in listOf("привет", "здравствуй", "здравствуйте", "салют", "добрый день", "доброе утро", "добрый вечер")) {
            return "Привет! Я ваш персональный локальный голосовой помощник. Работаю прямо на вашем телефоне без интернета. Чем могу помочь?"
        }

        if (lower.contains("кто ты") || lower.contains("как тебя зовут") || lower.contains("что ты умеешь")) {
            return "Я ваш персональный голосовой ИИ. Я умею распознавать русскую речь, отвечать на вопросы, считать, вести диалог офлайн на телефоне, а при включённом компьютере подключаться к мощной модели. Также по команде «Найди в интернете» могу искать информацию в сети."
        }

        // Math calculations
        val mathResult = tryEvaluateMath(lower)
        if (mathResult != null) {
            return mathResult
        }

        // Device & battery info prompt
        if (lower.contains("телефон") || lower.contains("infinix") || lower.contains("характеристик")) {
            return "Ваш смартфон — Infinix Note 30 на базе процессора MediaTek Helio G99 с 8 ГБ оперативной памяти. Этой мощности достаточно для локальной работы."
        }

        // Memory / Status
        if (lower.contains("статус") || lower.contains("режим работы") || lower.contains("где ты работаешь")) {
            return "Сейчас включён автономный режим: распознавание речи, обработка запросов и голос работают на вашем смартфоне без интернета."
        }

        // Helpful conversational offline replies
        return generateOfflineConversationalAnswer(trimmed, lower)
    }

    private fun tryEvaluateMath(text: String): String? {
        val clean = text.replace("сколько будет", "")
            .replace("посчитай", "")
            .replace("вычисли", "")
            .trim()

        val plusMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(?:\+|\bплюс\b)\s*(\d+(?:[.,]\d+)?)""").find(clean)
        if (plusMatch != null) {
            val a = plusMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
            val b = plusMatch.groupValues[2].replace(',', '.').toDoubleOrNull()
            if (a != null && b != null) return "Результат: ${formatNum(a + b)}"
        }

        val minusMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(?:-|\bминус\b)\s*(\d+(?:[.,]\d+)?)""").find(clean)
        if (minusMatch != null) {
            val a = minusMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
            val b = minusMatch.groupValues[2].replace(',', '.').toDoubleOrNull()
            if (a != null && b != null) return "Результат: ${formatNum(a - b)}"
        }

        val multMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(?:\*|x|х|\bумножить на\b|\bна\b)\s*(\d+(?:[.,]\d+)?)""").find(clean)
        if (multMatch != null) {
            val a = multMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
            val b = multMatch.groupValues[2].replace(',', '.').toDoubleOrNull()
            if (a != null && b != null) return "Результат: ${formatNum(a * b)}"
        }

        val divMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(?:/|:|\bразделить на\b|\bподелить на\b)\s*(\d+(?:[.,]\d+)?)""").find(clean)
        if (divMatch != null) {
            val a = divMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
            val b = divMatch.groupValues[2].replace(',', '.').toDoubleOrNull()
            if (a != null && b != null) {
                if (b == 0.0) return "На ноль делить нельзя."
                return "Результат: ${formatNum(a / b)}"
            }
        }

        return null
    }

    private fun formatNum(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale("ru"), "%.2f", value)
        }
    }

    private fun generateOfflineConversationalAnswer(original: String, lower: String): String {
        return when {
            lower.contains("как дела") || lower.contains("как поживаешь") ->
                "Всё отлично! Все системы функционируют автономно и готовы к работе. Что вас интересует?"
            lower.contains("спасибо") || lower.contains("благодарю") ->
                "Всегда рад помочь! Обращайтесь в любое время."
            lower.contains("пока") || lower.contains("до свидания") ->
                "До связи! Нажмите микрофон, когда я снова понадоблюсь."
            lower.contains("погода") ->
                "Для точного прогноза погоды требуется интернет. Попробуйте сказать: «Найди в интернете погоду в [городе]»."
            lower.contains("новост") ->
                "Чтобы узнать свежие новости, скажите: «Найди в интернете последние новости»."
            lower.contains("помощь") || lower.contains("команды") ->
                "Вы можете спросить меня: сколько времени, какое число, попросить посчитать пример, поговорить или сказать «Найди в интернете [запрос]» для веб-поиска."
            else ->
                "Запрос принят локально на телефоне: «$original». Если подключить компьютер к сети в настройках, я смогу дать более развёрнутый ответ через мощную модель Qwen2.5."
        }
    }
}
