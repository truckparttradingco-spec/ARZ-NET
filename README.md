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

