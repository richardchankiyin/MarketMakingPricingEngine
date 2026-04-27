package com.richard.marketmakingpricing;

public class PricingEngine implements MarketUpdateListener, SignalListener {
    private final SignalEmitter signalEmitter;
    private final PriceAggregator aggregator;
    
    private final double tickSize;
    private final double minProfitMargin;
    private final double maxSignalSkew;

    private volatile double currentSignal = 0.0;
    private volatile double bestBid = 0.0;
    private volatile double bestAsk = 0.0;

    private double myBid;
    private double myAsk;

    public PricingEngine(PriceAggregator aggregator, SignalEmitter signalEmitter, 
                         double tickSize, double minProfitMargin, double maxSignalSkew) {
        this.aggregator = aggregator;
        this.signalEmitter = signalEmitter;
        this.tickSize = tickSize;
        this.minProfitMargin = minProfitMargin;
        this.maxSignalSkew = maxSignalSkew;

        this.aggregator.setListener(this);
        this.signalEmitter.setListener(this);
    }

    @Override
    public void onSignalChange(double newSignal) {
        this.currentSignal = newSignal;
        refreshQuote();
    }

    @Override
    public void onBookUpdate(double bid, int bSize, double ask, int aSize, double vwapBid, double vwapAsk) {
        this.bestBid = bid;
        this.bestAsk = ask;
        refreshQuote();
    }

    private void refreshQuote() {
        if (bestBid <= 0 || bestAsk <= 0 || bestBid >= bestAsk) return;

        // 1. Anchor to the Current Market Mid
        double mid = (bestBid + bestAsk) / 2.0;

        // 2. Skew Fair Value based on Alpha (Signal)
        double fairValue = mid + (currentSignal * maxSignalSkew);

        // 3. Determine IDEAL Quote (Spread around Skewed Fair Value)
        double idealBid = fairValue - (minProfitMargin / 2.0);
        double idealAsk = fairValue + (minProfitMargin / 2.0);

        // 4. Apply PROFITABILITY CLAMP (The Reality Check)
        // We MUST buy for less than we sell to LP (bestBid)
        // We MUST sell for more than we buy from LP (bestAsk)
        double clampedBid = Math.min(idealBid, bestBid - tickSize);
        double clampedAsk = Math.max(idealAsk, bestAsk + tickSize);

        // 5. High-Precision Tick Rounding
        double scale = 1.0 / tickSize;
        this.myBid = Math.round(clampedBid * scale) / scale;
        this.myAsk = Math.round(clampedAsk * scale) / scale;

        // 6. Safety Guard (Never cross own spread)
        if (this.myBid >= this.myAsk) {
            this.myBid = this.myAsk - tickSize;
        }
    }

    public double getMyBid() { return myBid; }
    public double getMyAsk() { return myAsk; }
}