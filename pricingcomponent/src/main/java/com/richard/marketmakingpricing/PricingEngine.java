package com.richard.marketmakingpricing;

import java.util.ArrayList;
import java.util.List;

public class PricingEngine implements MarketUpdateListener, SignalListener {
    private final SignalEmitter signalEmitter;
    private final PriceAggregator aggregator;
    
    private final double tickSize;
    private final double minProfitMargin;
    private final double maxSignalSkew;
    private final int quoteSize;

    // Volatiles for external state access (Getters)
    private volatile double currentSignal = 0.0;
    private volatile double bestBid = 0.0;
    private volatile double bestAsk = 0.0;

    private volatile double myBid;
    private volatile int myBidSize;
    private volatile double myAsk;
    private volatile int myAskSize;
    
    private volatile double idealBid;
    private volatile double idealAsk;

    private final List<PricingListener> listeners = new ArrayList<>();

    public PricingEngine(PriceAggregator aggregator, SignalEmitter signalEmitter, 
                         double tickSize, double minProfitMargin, double maxSignalSkew, int quoteSize) {
        this.aggregator = aggregator;
        this.signalEmitter = signalEmitter;
        this.tickSize = tickSize;
        this.minProfitMargin = minProfitMargin;
        this.maxSignalSkew = maxSignalSkew;
        this.quoteSize = quoteSize;

        this.aggregator.setListener(this);
        this.signalEmitter.setListener(this);
    }

    public void addListener(PricingListener listener) {
        this.listeners.add(listener);
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
        // Defensive check for uninitialized data
        if (bestBid <= 0 || bestAsk <= 0 || bestBid >= bestAsk) return;

        // 1. Calculations in Local Stack Frame
        double mid = (bestBid + bestAsk) / 2.0;
        double fairValue = mid + (currentSignal * maxSignalSkew);
        double scale = 1.0 / tickSize;

        double localIdealBid = Math.round((fairValue - (minProfitMargin / 2.0)) * scale) / scale;
        double localIdealAsk = Math.round((fairValue + (minProfitMargin / 2.0)) * scale) / scale;

        // 2. Clamping Logic
        double localMyBid = Math.min(localIdealBid, bestBid - tickSize);
        double localMyAsk = Math.max(localIdealAsk, bestAsk + tickSize);

        // Snap to tick precision
        localMyBid = Math.round(localMyBid * scale) / scale;
        localMyAsk = Math.round(localMyAsk * scale) / scale;

        // 3. Consistency Guard
        if (localMyBid >= localMyAsk) {
            localMyBid = localMyAsk - tickSize;
        }

        // 4. Update Volatile Fields for Getters
        this.idealBid = localIdealBid;
        this.idealAsk = localIdealAsk;
        this.myBid = localMyBid;
        this.myAsk = localMyAsk;
        this.myBidSize = quoteSize;
        this.myAskSize = quoteSize;

        // 5. Atomic Push to Listeners
        notifyListeners(localMyBid, quoteSize, localMyAsk, quoteSize, localIdealBid, localIdealAsk);
    }

    private void notifyListeners(double b, int bs, double a, int as, double ib, double ia) {
        for (PricingListener listener : listeners) {
            listener.onQuoteUpdate(b, bs, a, as, ib, ia);
        }
    }

    // --- Public Getters for Unit Testing and Logic Monitoring ---
    public double getMyBid() { return myBid; }
    public int getMyBidSize() { return myBidSize; }
    public double getMyAsk() { return myAsk; }
    public int getMyAskSize() { return myAskSize; }
    public double getIdealBid() { return idealBid; }
    public double getIdealAsk() { return idealAsk; }
}