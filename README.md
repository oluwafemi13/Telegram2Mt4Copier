# TG2MT5 Copier

An Android app that reads trading signals from your Telegram channels and either:

- **Alert only** – sends you a notification with the signal and a suggested lot size (free), or
- **Auto-trade** – places the trade on your MetaTrader 5 account instantly through [MetaApi](https://metaapi.cloud).

Example of a signal it understands:

```
XAUUSD 📉
Sell: 4054.5
TP: 4044.5
SL: 4064.5
```

It also understands `BUY`/`SELL`, `GOLD`, `TP1/TP2`, `@` prices and most forex pairs. Messages that only report "TP hit" / "SL hit" are logged and ignored (MT5 closes those trades itself).

## Install

1. On your Android phone, open <https://github.com/oluwafemi13/Telegram2Mt4Copier/releases/download/latest-apk/TG2MT5-Copier.apk>. This link always downloads the newest version. (It is also on the **Releases** page under **Latest APK**.)
2. Open the file. If Android asks, allow installing apps from your browser or file manager.
3. New versions install over the old one; your login and settings are kept.

## Setup

### 1. Telegram (one-time)

The app logs into **your own Telegram account**, so it needs a Telegram API ID:

1. Open <https://my.telegram.org> in a browser and log in with your phone number.
2. Tap **API development tools**, fill in any app title and short name (e.g. `TG2MT5 Copier`), platform **Android**, and create it.
3. In the app, open the **Telegram** tab and paste the **api_id** and **api_hash**, then tap **Save and connect**.
4. Enter your phone number, then the login code Telegram sends you (and your 2-step password if you have one).
5. Tick the channel(s) that post your signals.

Keep the API ID and hash private.

### 2. Settings

- **Mode:** Alert only or Auto-trade.
- **Account type:** Standard or Cent. For cent accounts, 1 cent lot = 0.01 standard lot by default (editable).
- **Lot size:** a fixed lot, or a risk % of equity (lot calculated from the distance to the signal's SL).
- **Symbol names:** if your broker uses names like `XAUUSDm` or `XAUUSDc`, set the suffix (`m`, `c`, …).
- **Orders:** market (instant) or pending at the signal price; which TP to use; skip if price moved too far; ignore old signals.

### 3. MetaApi (Auto-trade only)

1. Sign up at <https://app.metaapi.cloud> (Regular plan is enough; there is a 7-day trial).
2. Add your MT5 account (login, **master** password, broker server) and wait until it shows as deployed.
3. Create an API token.
4. In the app's **Settings**, paste the token and the MetaApi **account ID**, choose the region, and tap **Test connection**.

### 4. Test, then start

- In the **Test** tab, paste a real signal, tap **Check signal**, then **Preview order** to see the exact symbol, lot and prices that would be sent. Try **Place trade** on a **demo account** first.
- On the **Home** tab, tap **Allow running in the background**, then **Start copier**. A permanent notification shows that it is running. It restarts automatically after the phone reboots.

## How it is built

- Kotlin + Jetpack Compose Android app (`app/`).
- Telegram via Telegram's official [TDLib](https://github.com/tdlib/td), compiled from source by the **Build TDLib** workflow and published as a release.
- The **Build APK** workflow builds and tests the app on every push and publishes the APK under **Latest APK**.
- `app/release.jks` is the signing key, kept in the repository so every build can update the installed app. Anyone with the repository could sign an APK with it, so only install APKs from this repository's own Releases page.

Trading involves risk. Test on a demo account before using real money.
