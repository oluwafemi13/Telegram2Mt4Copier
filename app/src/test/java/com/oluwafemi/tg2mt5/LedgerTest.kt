package com.oluwafemi.tg2mt5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerTest {
    private fun sig(key: String, chat: Long, t: Long, symbol: String = "XAUUSD", buy: Boolean = false) =
        ChannelSignal(key, chat, "Gold VIP", t, symbol, buy, 4054.5, if (buy) 4044.5 else 4064.5, if (buy) 4064.5 else 4044.5, "signal")

    @Test
    fun replyLinksToThatSignal() {
        val a = sig("1:10", 1, 1000); val b = sig("1:20", 1, 2000)
        assertEquals("1:10", Ledger.findSignalFor(listOf(a, b), 1, "1:10", "SL hit", 3000)?.key)
    }

    @Test
    fun nonReplyLinksToNewestOpenSignalInSameChat() {
        val a = sig("1:10", 1, 1000); val b = sig("1:20", 1, 2000); val other = sig("2:5", 2, 2500)
        assertEquals("1:20", Ledger.findSignalFor(listOf(a, b, other), 1, null, "SL hit ❌", 3000)?.key)
    }

    @Test
    fun namedSymbolWins() {
        val gold = sig("1:10", 1, 1000); val eur = sig("1:20", 1, 2000, symbol = "EURUSD")
        assertEquals("1:10", Ledger.findSignalFor(listOf(gold, eur), 1, null, "GOLD TP hit ✅", 3000)?.key)
    }

    @Test
    fun differentSymbolDoesNotLink() {
        val gold = sig("1:10", 1, 1000)
        assertNull(Ledger.findSignalFor(listOf(gold), 1, null, "GBPJPY SL hit", 3000))
    }

    @Test
    fun alreadyClaimedSignalIsSkipped() {
        val a = sig("1:10", 1, 1000); val b = sig("1:20", 1, 2000).copy(claim = "win")
        assertEquals("1:10", Ledger.findSignalFor(listOf(a, b), 1, null, "SL hit", 3000)?.key)
    }

    @Test
    fun statsSeparateVisibleFromEverythingPosted() {
        val signals = listOf(
            sig("1:1", 1, 100).copy(claim = "win"),
            sig("1:2", 1, 200).copy(claim = "win"),
            sig("1:3", 1, 300).copy(claim = "loss", claimDeletedAt = 400),   // deleted loss
            sig("1:4", 1, 500).copy(verified = "loss"),                    // silent loss
            sig("1:5", 1, 600).copy(claim = "win", verified = "loss"),     // mismatch
        )
        val st = Ledger.stats(signals, emptyList(), 0, null)
        assertEquals(5, st.signals)
        assertEquals(3, st.visibleWins); assertEquals(0, st.visibleLosses); assertEquals(100, st.visibleRate)
        assertEquals(3, st.claimedWins); assertEquals(1, st.claimedLosses); assertEquals(75, st.claimedRate)
        assertEquals(1, st.deletedLossResults)
        assertEquals(1, st.silentLosses)
        assertEquals(1, st.mismatches)
        assertEquals(0, st.priceWins); assertEquals(2, st.priceLosses)
    }

    @Test
    fun priceCheckFindsWhichCameFirst() {
        val s = sig("1:1", 1, 60_000) // SELL, SL 4064.5, TP 4044.5
        val win = listOf(Candle(60_000, 4056.0, 4050.0), Candle(60_060, 4052.0, 4044.0))
        assertEquals("win", Ledger.verify(s, win, 60_200)?.first)
        val loss = listOf(Candle(60_000, 4060.0, 4050.0), Candle(60_060, 4065.0, 4058.0), Candle(60_120, 4050.0, 4040.0))
        assertEquals("loss", Ledger.verify(s, loss, 60_200)?.first)
        val both = listOf(Candle(60_000, 4070.0, 4040.0))
        assertEquals("both", Ledger.verify(s, both, 60_200)?.first)
        val open = listOf(Candle(60_000, 4058.0, 4050.0))
        assertNull(Ledger.verify(s, open, 60_200))
    }

    @Test
    fun candlesBeforeTheSignalAreIgnored() {
        val s = sig("1:1", 1, 60_000)
        val before = listOf(Candle(59_880, 4070.0, 4060.0), Candle(60_000, 4056.0, 4050.0))
        assertNull(Ledger.verify(s, before, 60_100))
    }
}
