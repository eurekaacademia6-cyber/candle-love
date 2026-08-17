# Real Vision difference

The previous prototype could show an APK even when the camera layer was not actually reconstructing candle bodies. This version has a hard detection gate.

The app first detects actual red/green chart pixels, groups them into candle bodies, estimates wick extent, reconstructs normalized OHLC geometry, and checks detection quality. If fewer than 10 reliable candles are detected, the app shows SCAN AGAIN and does not make a directional prediction.

This does not make the predictor guaranteed or statistically proven. The training pipeline is included for real historical-data training and walk-forward validation.

Camera-only limits:
- hidden order-book liquidity is unavailable
- actual broker spread/execution price is unavailable
- external news is unavailable
