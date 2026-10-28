package com.oluwafemi.tg2mt5

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** kind: signal, trade, alert, outcome, error, info */
data class LogEntry(val time: Long, val kind: String, val title: String, val detail: String)

/** Keeps the last 300 events on the phone so you can see what the copier did. */
class SignalLog(context: Context) {
    private val file = File(context.filesDir, "signal_log.json")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LogEntry>> = _entries

    @Synchronized
    fun add(kind: String, title: String, detail: String = "") {
        val list = (listOf(LogEntry(System.currentTimeMillis(), kind, title, detail)) + _entries.value).take(300)
        _entries.value = list
        save(list)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save(emptyList())
    }

    private fun load(): List<LogEntry> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LogEntry(o.getLong("t"), o.getString("k"), o.getString("ti"), o.optString("d"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<LogEntry>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("t", it.time).put("k", it.kind).put("ti", it.title).put("d", it.detail)) }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
