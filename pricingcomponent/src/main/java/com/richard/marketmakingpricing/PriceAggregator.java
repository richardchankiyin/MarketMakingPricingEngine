package com.richard.marketmakingpricing;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class PriceAggregator {
    // Price -> LinkedHashMap<LP_ID, Size> for FIFO priority at each level
    private final NavigableMap<Double, Map<String, Integer>> bids = new ConcurrentSkipListMap<>(Collections.reverseOrder());
    private final NavigableMap<Double, Map<String, Integer>> asks = new ConcurrentSkipListMap<>();
    
    private final Map<String, LPQuoteRecord> lpRegistry = new ConcurrentHashMap<>();
    private MarketUpdateListener listener;

    public void setListener(MarketUpdateListener listener) {
        this.listener = listener;
    }

    public synchronized void onUpdate(String lpId, double bid, int bidSize, double ask, int askSize) {
        // 1. Remove old prices for this LP
        LPQuoteRecord old = lpRegistry.get(lpId);
        if (old != null) {
            removeLiquidity(bids, old.lastBid, lpId);
            removeLiquidity(asks, old.lastAsk, lpId);
        }

        // 2. Add new liquidity (LinkedHashMap maintains FIFO)
        bids.computeIfAbsent(bid, k -> new LinkedHashMap<>()).put(lpId, bidSize);
        asks.computeIfAbsent(ask, k -> new HashMap<>()).put(lpId, askSize);

        // 3. Update Registry
        lpRegistry.put(lpId, new LPQuoteRecord(bid, ask));

        // 4. Trigger the notification
        triggerUpdate();
    }

    /**
     * Aggregates the top-of-book volume and notifies the PricingEngine.
     */
    private void triggerUpdate() {
        if (listener == null || bids.isEmpty() || asks.isEmpty()) {
            return;
        }

        // Extract Best Bid and sum all volume at that price
        double bestBid = bids.firstKey();
        int totalBidSize = bids.get(bestBid).values().stream().mapToInt(Integer::intValue).sum();

        // Extract Best Ask and sum all volume at that price
        double bestAsk = asks.firstKey();
        int totalAskSize = asks.get(bestAsk).values().stream().mapToInt(Integer::intValue).sum();

        // Push to PricingEngine
        listener.onBookUpdate(bestBid, totalBidSize, bestAsk, totalAskSize);
    }

    private void removeLiquidity(NavigableMap<Double, Map<String, Integer>> book, double price, String lpId) {
        Map<String, Integer> levels = book.get(price);
        if (levels != null) {
            levels.remove(lpId);
            if (levels.isEmpty()) {
                book.remove(price);
            }
        }
    }

    // Sweep logic for OMS
    public double sweepBook(boolean buyFromLPs, int targetQty) {
        NavigableMap<Double, Map<String, Integer>> side = buyFromLPs ? asks : bids;
        int remaining = targetQty;
        double totalCost = 0;

        for (Map.Entry<Double, Map<String, Integer>> level : side.entrySet()) {
            double price = level.getKey();
            for (int qtyAtLP : level.getValue().values()) {
                int take = Math.min(remaining, qtyAtLP);
                totalCost += (take * price);
                remaining -= take;
                if (remaining <= 0) break;
            }
            if (remaining <= 0) break;
        }
        return (targetQty == remaining) ? 0.0 : totalCost / (targetQty - remaining);
    }

    // Getters for Unit Tests
    public double getBestBid() { return bids.isEmpty() ? 0.0 : bids.firstKey(); }
    public double getBestAsk() { return asks.isEmpty() ? Double.MAX_VALUE : asks.firstKey(); }
    public int getBidDepth() { return bids.size(); }
    public int getAskDepth() { return asks.size(); }

    private static class LPQuoteRecord {
        final double lastBid, lastAsk;
        LPQuoteRecord(double b, double a) { this.lastBid = b; this.lastAsk = a; }
    }
}