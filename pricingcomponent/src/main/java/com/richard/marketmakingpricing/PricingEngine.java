package com.richard.marketmakingpricing;

import java.util.concurrent.atomic.AtomicReference;

public class PricingEngine implements MarketUpdateListener {

    private final SignalEmitter signalEmitter;
    private final LocalBookView localBook = new LocalBookView();
    private final AtomicReference<Quote> currentActiveQuote = new AtomicReference<>();
    
    private final Quote[] quotePool;
    private int poolIndex = 0;
    private static final int POOL_SIZE = 1024;

    private static final double DEFAULT_SPREAD = 0.02;
    private static final int MY_QUOTE_SIZE = 1000;
    private static final double TICK_SIZE = 0.001;

    public PricingEngine(PriceAggregator aggregator, SignalEmitter signalEmitter) {
        this.signalEmitter = signalEmitter;
        this.quotePool = new Quote[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) {
            quotePool[i] = new Quote();
        }

        aggregator.setListener(this);
        signalEmitter.setListener(this::onSignalUpdate);
    }

    @Override
    public void onBookUpdate(double bid, int bSize, double ask, int aSize,  double vwapBid, double vwapAsk) {
        localBook.update(bid, bSize, ask, aSize);
        refreshQuote();
    }

    public void onSignalUpdate(double newSignal) {
        refreshQuote();
    }

    private void refreshQuote() {
        if (localBook.bestBid <= 0 || localBook.bestAsk >= Double.MAX_VALUE) return;

        // 1. Calculate Weighted Mid (Micro-Price)
        // This uses the bid/ask sizes to see which way the market is leaning.
        double totalVolume = (double) localBook.bidSize + localBook.askSize;
        double imbalanceWeight = localBook.bidSize / totalVolume;
        // If bidSize is huge, microMid will be closer to the Ask (Bullish)
        double microMid = (localBook.bestBid * (1 - imbalanceWeight)) + (localBook.bestAsk * imbalanceWeight);

        // 2. Incorporate Signal Skew
        double signal = signalEmitter.getCurrentSignal();
        double fairValue = microMid + (signal * 0.05);

        // 3. Dynamic Spread adjustment based on Liquidity
        // If liquidity is low (volatile), we widen the spread to protect ourselves.
        double spreadBuffer = (totalVolume < 500) ? DEFAULT_SPREAD * 1.5 : DEFAULT_SPREAD;

        double myBid = roundToTick(fairValue - (spreadBuffer / 2.0));
        double myAsk = roundToTick(fairValue + (spreadBuffer / 2.0));

        // 4. Competitive boundaries (Price Improvement)
        // We try to "join" the best bid/ask or beat it by one tick if we want to be aggressive
        if (myBid >= localBook.bestAsk) myBid = localBook.bestAsk - TICK_SIZE;
        if (myAsk <= localBook.bestBid) myAsk = localBook.bestBid + TICK_SIZE;

        // 5. Publish
        Quote nextQuote = quotePool[poolIndex++ & (POOL_SIZE - 1)];
        nextQuote.update(myBid, MY_QUOTE_SIZE, myAsk, MY_QUOTE_SIZE, System.currentTimeMillis());

        currentActiveQuote.set(nextQuote);
    }

    private double roundToTick(double value) {
        return Math.round(value / TICK_SIZE) * TICK_SIZE;
    }

    public Quote getCurrentQuote() {
        return currentActiveQuote.get();
    }

    private static class LocalBookView {
        double bestBid;
        int bidSize;
        double bestAsk;
        int askSize;

        void update(double b, int bs, double a, int as) {
            this.bestBid = b;
            this.bidSize = bs;
            this.bestAsk = a;
            this.askSize = as;
        }
    }
}