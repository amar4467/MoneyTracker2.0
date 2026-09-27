package com.amar.moneytracker

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedBankNotification(
    val title: String,
    val amountCents: Long,
    val type: EntryType,
    val dateMillis: Long? = null
)

/** Conservative parser: only messages that look like a completed money movement are accepted. */
object BankNotificationParser {
    private val amountWithCurrency = Regex("(?i)(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val amountAfterVerb = Regex("(?i)(?:debited|credited|paid|spent|sent|received|withdrawn|charged)\\D{0,18}([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val incomeWords = Regex("(?i)\\b(credited|received|deposited|refund|reversed)\\b")
    private val expenseWords = Regex("(?i)\\b(debited|spent|paid|sent|withdrawn|charged|purchase|purchased)\\b")
    private val noiseWords = Regex("(?i)\\b(otp|one[- ]time password|promotional|offer|cashback offer)\\b")

    fun parse(notificationTitle: String?, notificationText: String): ParsedBankNotification? {
        // Some SMS/notification apps render message text with Mathematical/Unicode
        // look-alike letters. NFKC maps those back to ordinary ASCII characters so
        // words such as “𝖽𝖾𝖻𝗂𝗍𝖾𝖽” still match the transaction rules.
        val body = Normalizer.normalize(notificationText, Normalizer.Form.NFKC)
            // Strip invisible/non-ASCII look-alike characters that some messaging
            // apps insert between otherwise normal transaction words.
            .filter { it.code in 32..126 || it == '₹' }
            .replace(Regex("\\s+"), " ").trim()
        if (body.length < 6 || noiseWords.containsMatchIn(body)) return null
        val income = incomeWords.containsMatchIn(body)
        val expense = expenseWords.containsMatchIn(body)
        if (income == expense) return null
        val amountText = amountWithCurrency.find(body)?.groupValues?.get(1)
            ?: amountAfterVerb.find(body)?.groupValues?.get(1)
            ?: return null
        val cents = runCatching { BigDecimal(amountText.replace(",", "")).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }.getOrNull()
            ?: return null
        if (cents <= 0) return null
        val merchant = Regex("(?i)(?:to|at|from)\\s+([A-Za-z0-9][A-Za-z0-9 &'._-]{1,38})")
            .find(body)?.groupValues?.get(1)?.replace(Regex("(?i)\\s+info\\s*$"), "")?.trim()?.trimEnd('.', ',', ';')
        val title = merchant?.takeIf { it.isNotBlank() } ?: notificationTitle?.trim()?.takeIf { it.isNotBlank() } ?: "Bank transaction"
        val dateMillis = Regex("(?i)\\bon\\s+(\\d{1,2})[-/]([A-Za-z]{3,9})[-/](\\d{4})")
            .find(body)?.let { match ->
                val dateText = match.groupValues.drop(1).joinToString("-")
                listOf("d-MMM-yyyy", "d-MMMM-yyyy", "d-MM-yyyy").firstNotNullOfOrNull { pattern ->
                    runCatching { LocalDate.parse(dateText, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) }.getOrNull()
                }
            }?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        return ParsedBankNotification(title, cents, if (income) EntryType.INCOME else EntryType.EXPENSE, dateMillis)
    }
}
