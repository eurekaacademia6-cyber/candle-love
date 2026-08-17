package com.quotexrealvision.ai;

public final class Candle {
    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final boolean bullish;
    public final double detectionConfidence;

    public Candle(double open, double high, double low, double close,
                  boolean bullish, double detectionConfidence) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.bullish = bullish;
        this.detectionConfidence = detectionConfidence;
    }

    public double range() { return Math.max(1e-9, high - low); }
    public double body() { return close - open; }
    public double bodyAbs() { return Math.abs(body()); }
    public double upperWick() { return Math.max(0.0, high - Math.max(open, close)); }
    public double lowerWick() { return Math.max(0.0, Math.min(open, close) - low); }
    public double closePosition() { return (close - low) / range(); }
}
