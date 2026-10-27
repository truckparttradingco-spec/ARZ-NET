# ARZ-NET

ARZ-NET is an Android application for tracking global market prices and Iranian free-market exchange rates.

## Planned Features

- Global gold spot price (XAU/USD)
- Bitcoin price (BTC/USD)
- Iranian free-market USD exchange rates
- Buy, sell, and remittance rate display when reliable data is available
- Gold price calculations for 18K and 24K gold in Iranian rial
- Source attribution and last-updated timestamps
- Automated Android APK builds using GitHub Actions

## Calculation Reference

- 1 troy ounce = 31.1034768 grams
- 24K gold per gram = XAU/USD price / 31.1034768 × USD/IRR
- 18K gold per gram = 24K gold per gram × 0.75
- Bitcoin in IRR = BTC/USD × USD/IRR
- 1 toman = 10 Iranian rials

## Data Accuracy

Live prices will be shown only when data is available from configured sources. The application must distinguish live data from unavailable or stale data and must not present sample values as real market prices.

## Build

The Android APK is built automatically through GitHub Actions.


## Home-screen widget (v1.1)

- Adds an Android home-screen widget for USD free-market, 18K gold, Emami coin, and USDT.
- The widget uses the same `kwgt-proxy.php` endpoint as the app.
- Prices are displayed only when the source timestamp is present and no older than 60 seconds. Missing or stale prices are not shown as live prices.
- Tap the refresh icon to request an update. Background refresh is scheduled through WorkManager (Android may defer it based on battery and network conditions; its periodic minimum is 15 minutes).
- App ID remains `com.arznet.prices`; version is incremented to `1.1` (`versionCode 2`).

After installing the APK, press and hold an empty area on the Android home screen, open **Widgets**, find **ARZ-NET قیمت‌ها**, then drag the widget onto the home screen.
