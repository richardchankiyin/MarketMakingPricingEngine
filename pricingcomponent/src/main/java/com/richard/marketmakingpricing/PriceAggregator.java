package com.richard.marketmakingpricing;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class PriceAggregator {
    private final NavigableMap<Double, Map<String, Integer>> bids = new ConcurrentSkipListMap<>(Collections.reverseOrder());
    private final NavigableMap<Double, Map<String, Integer>> asks = new ConcurrentSkipListMap<>();
    private final Map<String, LPQuoteRecord> lpRegistry;
    
    private final ReentrantLock lock = new ReentrantLock();
    private MarketUpdateListener listener;
    private final int lpCount;

    public PriceAggregator() {
        this(5);
    }

    public PriceAggregator(int lpCount) {
        this.lpCount = lpCount;
        this.lpRegistry = new ConcurrentHashMap<>(lpCount);
    }

    public void setListener(MarketUpdateListener listener) {
        this.listener = listener;
    }

    public void onUpdate(String lpId, double bid, int bidSize, double ask, int askSize) {
        LPQuoteRecord record = lpRegistry.computeIfAbsent(lpId, k -> new LPQuoteRecord());

        try {
            if (lock.tryLock(5, TimeUnit.MILLISECONDS)) {
                try {
                    if (record.hasValidPrices()) {
                        removeLiquidity(bids, record.lastBid, lpId);
                        removeLiquidity(asks, record.lastAsk, lpId);
                    }

                    // Store new values
                    bids.computeIfAbsent(bid, k -> new LinkedHashMap<>(lpCount)).put(lpId, bidSize);
                    asks.computeIfAbsent(ask, k -> new LinkedHashMap<>(lpCount)).put(lpId, askSize);
                    
                    record.update(bid, bidSize, ask, askSize);
                    triggerUpdate(); 
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void triggerUpdate() {
        if (listener == null || bids.isEmpty() || asks.isEmpty()) return;

        // Best Bid Calculation
        double bb = bids.firstKey();
        Map<String, Integer> bidLevel = bids.get(bb);
        int totalBidSize = 0;
        
        if (bidLevel != null) {
            // Using explicit iterator to avoid hidden stream/lambda allocations
            // JIT Escape Analysis typically scalar-replaces this iterator
            for (Integer val : bidLevel.values()) {
                if (val != null) {
                    totalBidSize += val.intValue(); 
                }
            }
        }
        
        // Best Ask Calculation
        double ba = asks.firstKey();
        Map<String, Integer> askLevel = asks.get(ba);
        int totalAskSize = 0;
        
        if (askLevel != null) {
            for (Integer val : askLevel.values()) {
                if (val != null) {
                    totalAskSize += val.intValue();
                }
            }
        }

        listener.onBookUpdate(bb, totalBidSize, ba, totalAskSize);
    }

    private void removeLiquidity(NavigableMap<Double, Map<String, Integer>> book, double price, String lpId) {
        Map<String, Integer> levels = book.get(price);
        if (levels != null) {
            levels.remove(lpId);
            if (levels.isEmpty()) book.remove(price);
        }
    }

    public double sweepBook(boolean buyFromLPs, int targetQty) {
        NavigableMap<Double, Map<String, Integer>> side = buyFromLPs ? asks : bids;
        int remaining = targetQty;
        double totalCost = 0;

        for (Map.Entry<Double, Map<String, Integer>> level : side.entrySet()) {
            double price = level.getKey();
            for (Integer qtyAtLP : level.getValue().values()) {
                int qty = (qtyAtLP != null) ? qtyAtLP.intValue() : 0;
                int take = Math.min(remaining, qty);
                totalCost += (take * price);
                remaining -= take;
                if (remaining <= 0) break;
            }
            if (remaining <= 0) break;
        }
        return (targetQty == remaining) ? 0.0 : totalCost / (targetQty - remaining);
    }

    private static class LPQuoteRecord {
        double lastBid = -1, lastAsk = -1;
        int lastBidSize, lastAskSize;
        double prevBid, prevAsk;
        int prevBidSize, prevAskSize;

        void update(double b, int bs, double a, int as) {
            this.prevBid = this.lastBid;
            this.prevBidSize = this.lastBidSize;
            this.prevAsk = this.lastAsk;
            this.prevAskSize = this.lastAskSize;

            this.lastBid = b;
            this.lastBidSize = bs;
            this.lastAsk = a;
            this.lastAskSize = as;
        }
        boolean hasValidPrices() { return lastBid != -1; }
    }

    // Getters for Test/Audit
    public double getBestBid() { return bids.isEmpty() ? 0.0 : bids.firstKey(); }
    public double getBestAsk() { return asks.isEmpty() ? Double.MAX_VALUE : asks.firstKey(); }
}