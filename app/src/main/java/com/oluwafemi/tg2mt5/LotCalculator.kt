package com.oluwafemi.tg2mt5

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class LotResult(val lots: Double, val note: String, val ok: Boolean = true)

object LotCalculator {

    /**
     * Exact lot size for risk-% mode using values reported by the broker through MetaApi.
     * equity and lossTickValue are both in the account's own currency (USC on cent accounts),
     * so this works the same for cent and standard accounts.
     */
    fun riskLots(
        equity: Double,
        riskPercent: Double,
        entry: Double,
        stopLoss: Double,
        tickSize: Double,
        lossTickValue: Double,
        minVolume: Double,
        maxVolume: Double,
        volumeStep: Double,
        useMinLotIfTooSmall: Boolean,
        maxLotSetting: Double,
    ): LotResult {
        val distance = abs(entry - stopLoss)
        if (distance <= 0 || tickSize <= 0 || lossTickValue <= 0) return LotResult(0.0, "Cannot calculate risk (missing price data)", false)
        val riskMoney = equity * riskPercent / 100.0
        val lossPerLot = distance / tickSize * lossTickValue
        val raw = riskMoney / lossPerLot
        return finish(raw, minVolume, maxVolume, volumeStep, useMinLotIfTooSmall, maxLotSetting,
            "risk ${fmt(riskPercent)}% of ${fmt(round2(equity))} = ${fmt(round2(riskMoney))}, loss per lot ${fmt(round2(lossPerLot))}")
    }

    /**
     * Estimate used in Alert-only mode, where there is no broker connection.
     * Works for USD-quoted symbols (XAUUSD, EURUSD, ...) and USD-based pairs (USDJPY, ...).
     * equity is in the account's currency: USD for standard, USC (cents) for cent accounts.
     */
    fun estimateLots(
        symbol: String,
        equity: Double,
        isCent: Boolean,
        centFactor: Double,
        riskPercent: Double,
        entry: Double,
        stopLoss: Double,
    ): LotResult {
        val contract = standardContractSize(symbol) ?: return LotResult(0.0, "No estimate for $symbol", false)
        val equityUsd = if (isCent) equity / 100.0 else equity
        val riskUsd = equityUsd * riskPercent / 100.0
        var lossPerStdLot = abs(entry - stopLoss) * contract
        when {
            symbol.endsWith("USD") -> {}
            symbol.startsWith("USD") -> lossPerStdLot /= entry
            else -> return LotResult(0.0, "No estimate for $symbol (not a USD pair)", false)
        }
        if (lossPerStdLot <= 0) return LotResult(0.0, "Entry and SL are equal", false)
        val stdLots = riskUsd / lossPerStdLot
        val lots = if (isCent) stdLots / centFactor else stdLots
        val stepped = floor(lots / 0.01 + 1e-9) * 0.01
        return LotResult(round2(stepped), "estimate: risk ${fmt(riskPercent)}% = ${fmt(round2(riskUsd))} USD", stepped > 0)
    }

    fun standardContractSize(symbol: String): Double? = when {
        symbol.startsWith("XAU") -> 100.0
        symbol.startsWith("XAG") -> 5000.0
        symbol.length == 6 && symbol.all { it.isLetter() } && !symbol.startsWith("BTC") && !symbol.startsWith("ETH") -> 100000.0
        else -> null
    }

    fun finish(raw: Double, minV: Double, maxV: Double, step: Double, useMin: Boolean, maxSetting: Double, why: String): LotResult {
        val s = if (step > 0) step else 0.01
        var lots = floor(raw / s + 1e-9) * s
        var note = "$why → ${fmt(round8(raw))} lots"
        if (lots < minV) {
            if (!useMin) return LotResult(0.0, "$note, below broker minimum ${fmt(minV)}. Trade skipped.", false)
            lots = minV
            note += ", raised to broker minimum ${fmt(minV)}"
        }
        if (maxSetting > 0 && lots > maxSetting) { lots = maxSetting; note += ", capped at your max ${fmt(maxSetting)}" }
        if (maxV > 0) lots = min(lots, maxV)
        return LotResult(round8(max(lots, 0.0)), note)
    }

    private fun round2(v: Double) = round(v * 100) / 100
    private fun round8(v: Double) = round(v * 1e8) / 1e8
}
