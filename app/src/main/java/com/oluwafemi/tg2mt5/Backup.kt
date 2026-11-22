package com.oluwafemi.tg2mt5

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves and restores everything except the Telegram login session, as one JSON file. */
object Backup {
    private val files = listOf("channel_ledger.json", "signal_log.json", "open_trades.json")

    fun export(app: App): String {
        val prefs = JSONObject()
        for ((k, v) in app.prefs.exportAll()) {
            val o = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            }
            if (o != null) prefs.put(k, o)
        }
        val data = JSONObject()
        for (f in files) {
            val file = File(app.filesDir, f)
            if (file.exists()) data.put(f, file.readText())
        }
        return JSONObject()
            .put("app", "TG2MT5 Copier").put("format", 1).put("version", BuildConfig.VERSION_NAME)
            .put("flavor", if (app.strictPairs) "pairs" else "flexible").put("createdAt", System.currentTimeMillis())
            .put("prefs", prefs).put("setups", app.setups.toJson()).put("files", data)
            .toString(2)
    }

    /** Returns a message for the user. */
    fun restore(app: App, text: String): String {
        val root = JSONObject(text)
        if (root.optString("app") != "TG2MT5 Copier") return "✖ This isn't a TG2MT5 Copier backup file."
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        val values = mutableMapOf<String, Any?>()
        for (k in prefsJson.keys()) {
            val o = prefsJson.getJSONObject(k)
            values[k] = when (o.optString("t")) {
                "b" -> o.getBoolean("v")
                "i" -> o.getInt("v")
                "l" -> o.getLong("v")
                "f" -> o.getDouble("v").toFloat()
                "s" -> o.getString("v")
                "set" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> null
            }
        }
        values["copier_enabled"] = false // the user turns it on again after checking
        app.prefs.importAll(values)

        root.optJSONObject("files")?.let { f ->
            for (name in files) if (f.has(name)) File(app.filesDir, name).writeText(f.getString(name))
        }
        app.ledger.reload()
        app.log.reload()

        val s = root.optJSONObject("setups") ?: JSONObject()
        val tmp = SetupStoreData.parse(s)
        val (accounts, unlinked) = if (app.strictPairs) SetupStoreData.enforcePairs(tmp.second) else tmp.second to 0
        app.setups.replaceAll(tmp.first, accounts)
        CopierService.stop(app)
        app.log.add("info", "Backup restored (${tmp.first.size} channels, ${accounts.size} MT5 accounts)", if (unlinked > 0) "$unlinked account(s) were unlinked because the Pairs version allows one account per channel." else "")
        return "✔ Restored ${tmp.first.size} channel(s) and ${accounts.size} MT5 account(s)." +
            (if (unlinked > 0) " $unlinked account(s) were unlinked because this Pairs version allows one account per channel." else "") +
            " Log in to Telegram again if needed, then turn the copier on."
    }
}

/** Pure helpers for restoring setups, unit-tested. */
object SetupStoreData {
    fun parse(o: JSONObject): Pair<List<Channel>, List<Mt5Account>> {
        val c = o.optJSONArray("channels") ?: JSONArray()
        val a = o.optJSONArray("accounts") ?: JSONArray()
        return (0 until c.length()).map { Channel.fromJson(c.getJSONObject(it)) } to (0 until a.length()).map { Mt5Account.fromJson(a.getJSONObject(it)) }
    }

    /** Keeps the first account per channel linked and unlinks the rest. Returns the accounts and how many were unlinked. */
    fun enforcePairs(accounts: List<Mt5Account>): Pair<List<Mt5Account>, Int> {
        val seen = mutableSetOf<Long>()
        var unlinked = 0
        val out = accounts.map { a ->
            val ch = a.channelId
            if (ch == null) a
            else if (seen.add(ch)) a
            else { unlinked++; a.copy(channelId = null, copyOn = false) }
        }
        return out to unlinked
    }
}
