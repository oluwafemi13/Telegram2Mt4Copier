package com.oluwafemi.tg2mt5

import android.content.Context
import android.content.SharedPreferences

/** All user settings, stored privately on the phone. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = p.getString(key, def) ?: def
    private fun dbl(key: String, def: Double) = str(key, def.toString()).replace(',', '.').toDoubleOrNull() ?: def
    private fun put(key: String, v: String) = p.edit().putString(key, v).apply()
    private fun putB(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    // Telegram
    var tgApiId: String get() = str("tg_api_id", ""); set(v) = put("tg_api_id", v.trim())
    var tgApiHash: String get() = str("tg_api_hash", ""); set(v) = put("tg_api_hash", v.trim())
    var channelIds: Set<Long>
        get() = (p.getStringSet("channels", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = p.edit().putStringSet("channels", v.map { it.toString() }.toSet()).apply()

    // Mode: "alert" or "auto"
    var mode: String get() = str("mode", "alert"); set(v) = put("mode", v)
    val isAuto get() = mode == "auto"
    var copierEnabled: Boolean get() = p.getBoolean("copier_enabled", false); set(v) = putB("copier_enabled", v)

    // MetaApi
    var metaToken: String get() = str("meta_token", ""); set(v) = put("meta_token", v.trim())
    var metaAccountId: String get() = str("meta_account", ""); set(v) = put("meta_account", v.trim())
    var metaRegion: String get() = str("meta_region", "new-york"); set(v) = put("meta_region", v)

    // Account
    var accountType: String get() = str("account_type", "standard"); set(v) = put("account_type", v)
    val isCent get() = accountType == "cent"
    var centFactorText: String get() = str("cent_factor", "0.01"); set(v) = put("cent_factor", v)
    val centFactor get() = dbl("cent_factor", 0.01).takeIf { it > 0 } ?: 0.01

    // Lots: "fixed" or "risk"
    var lotMode: String get() = str("lot_mode", "fixed"); set(v) = put("lot_mode", v)
    var fixedLotText: String get() = str("fixed_lot", "0.01"); set(v) = put("fixed_lot", v)
    val fixedLot get() = dbl("fixed_lot", 0.01)
    var riskPercentText: String get() = str("risk_pct", "1"); set(v) = put("risk_pct", v)
    val riskPercent get() = dbl("risk_pct", 1.0)
    var maxLotText: String get() = str("max_lot", "0"); set(v) = put("max_lot", v)
    val maxLot get() = dbl("max_lot", 0.0)
    var useMinLot: Boolean get() = p.getBoolean("use_min_lot", true); set(v) = putB("use_min_lot", v)
    var alertEquityText: String get() = str("alert_equity", ""); set(v) = put("alert_equity", v)
    val alertEquity get() = dbl("alert_equity", 0.0)

    // Symbols
    var symbolPrefix: String get() = str("sym_prefix", ""); set(v) = put("sym_prefix", v.trim())
    var symbolSuffix: String get() = str("sym_suffix", ""); set(v) = put("sym_suffix", v.trim())
    var symbolMap: String get() = str("sym_map", ""); set(v) = put("sym_map", v)

    // Orders
    var orderType: String get() = str("order_type", "market"); set(v) = put("order_type", v)   // market | pending
    var tpChoice: String get() = str("tp_choice", "first"); set(v) = put("tp_choice", v)      // first | last
    var maxDistanceText: String get() = str("max_distance", "0"); set(v) = put("max_distance", v)
    val maxDistance get() = dbl("max_distance", 0.0)
    var maxAgeText: String get() = str("max_age_min", "3"); set(v) = put("max_age_min", v)
    val maxAgeMinutes get() = dbl("max_age_min", 3.0)

    /** Turns a signal symbol (e.g. XAUUSD) into the broker's name (e.g. XAUUSDm). */
    fun brokerSymbol(symbol: String): String {
        for (line in symbolMap.lines()) {
            val parts = line.split("=", ":").map { it.trim() }
            if (parts.size == 2 && parts[0].equals(symbol, ignoreCase = true) && parts[1].isNotEmpty()) return parts[1]
        }
        return symbolPrefix + symbol + symbolSuffix
    }

    // Duplicate protection: remember the last 500 processed messages.
    fun alreadyProcessed(key: String): Boolean {
        val list = str("processed", "").split(',').filter { it.isNotEmpty() }
        if (key in list) return true
        put("processed", (list.takeLast(499) + key).joinToString(","))
        return false
    }
}
