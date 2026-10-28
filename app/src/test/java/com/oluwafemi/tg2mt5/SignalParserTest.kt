package com.oluwafemi.tg2mt5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalParserTest {

    private fun trade(text: String): Signal {
        val r = SignalParser.parse(text)
        assertTrue("Expected a trade but got $r", r is ParseResult.Trade)
        return (r as ParseResult.Trade).signal
    }

    @Test
    fun parsesTheChannelExample() {
        val s = trade("XAUUSD 📉\n\nSell: 4054.5\n\nTP: 4044.5\n\nSL: 4064.5\n\nRisk 1-2% of total Equity")
        assertEquals("XAUUSD", s.symbol)
        assertFalse(s.isBuy)
        assertEquals(4054.5, s.entry!!, 1e-9)
        assertEquals(listOf(4044.5), s.takeProfits)
        assertEquals(4064.5, s.stopLoss, 1e-9)
    }

    @Test
    fun parsesBuyWithEmojiAndMultipleTps() {
        val s = trade("🔥 GOLD BUY NOW @ 4010\nTP1: 4020\nTP2 4030\nSL 4000")
        assertEquals("XAUUSD", s.symbol)
        assertTrue(s.isBuy)
        assertEquals(4010.0, s.entry!!, 1e-9)
        assertEquals(listOf(4020.0, 4030.0), s.takeProfits)
        assertEquals(4000.0, s.stopLoss, 1e-9)
    }

    @Test
    fun tpWithoutColonIsNotMisread() {
        val s = trade("EURUSD buy 1.0850\nTP 1.0900\nSL 1.0800")
        assertEquals("EURUSD", s.symbol)
        assertEquals(listOf(1.09), s.takeProfits)
    }

    @Test
    fun forexPairWithSlash() {
        val s = trade("GBP/JPY SELL 190.50\nTP 189.50\nSL 191.50")
        assertEquals("GBPJPY", s.symbol)
    }

    @Test
    fun resultMessagesAreOutcomes() {
        assertTrue(SignalParser.parse("XAUUSD TP hit ✅ +100 pips") is ParseResult.Outcome)
        assertTrue(SignalParser.parse("SL hit ❌") is ParseResult.Outcome)
    }

    @Test
    fun ordinaryChatIsIgnored() {
        assertTrue(SignalParser.parse("Good morning traders, market opens soon") is ParseResult.NotASignal)
    }

    @Test
    fun wrongSideLevelsAreRejected() {
        assertTrue(SignalParser.parse("XAUUSD Sell: 4054.5\nTP: 4064.5\nSL: 4044.5") is ParseResult.Invalid)
    }

    @Test
    fun missingSlIsRejected() {
        assertTrue(SignalParser.parse("XAUUSD Sell: 4054.5\nTP: 4044.5") is ParseResult.Invalid)
    }
}

class LotCalculatorTest {

    @Test
    fun riskLotsStandardGold() {
        // $1,000 equity, 1% risk = $10. SL distance $10 on gold; tick 0.01 worth $1 per lot → $1,000 loss per lot.
        val r = LotCalculator.riskLots(1000.0, 1.0, 4054.5, 4064.5, 0.01, 1.0, 0.01, 100.0, 0.01, true, 0.0)
        assertTrue(r.ok)
        assertEquals(0.01, r.lots, 1e-9)
    }

    @Test
    fun riskLotsCentGold() {
        // Cent account: 100,000 USC ($1,000), 1% = 1,000 USC. Per cent lot a $10 move loses 1,000 USC (0.01 std lot = $10).
        // tick 0.01 worth 1 USC per cent lot → loss per lot = 1000/1 = 1000 USC → 1 cent lot.
        val r = LotCalculator.riskLots(100000.0, 1.0, 4054.5, 4064.5, 0.01, 1.0, 0.01, 200.0, 0.01, true, 0.0)
        assertEquals(1.0, r.lots, 1e-9)
    }

    @Test
    fun belowMinimumIsSkippedWhenNotAllowed() {
        val r = LotCalculator.riskLots(100.0, 1.0, 4054.5, 4064.5, 0.01, 1.0, 0.01, 100.0, 0.01, false, 0.0)
        assertFalse(r.ok)
    }

    @Test
    fun maxLotCaps() {
        val r = LotCalculator.riskLots(1_000_000.0, 2.0, 4054.5, 4064.5, 0.01, 1.0, 0.01, 100.0, 0.01, true, 0.5)
        assertEquals(0.5, r.lots, 1e-9)
    }

    @Test
    fun alertEstimateStandardAndCent() {
        val std = LotCalculator.estimateLots("XAUUSD", 1000.0, false, 0.01, 1.0, 4054.5, 4064.5)
        assertEquals(0.01, std.lots, 1e-9)
        val cent = LotCalculator.estimateLots("XAUUSD", 100000.0, true, 0.01, 1.0, 4054.5, 4064.5)
        assertEquals(1.0, cent.lots, 1e-9)
    }
}
