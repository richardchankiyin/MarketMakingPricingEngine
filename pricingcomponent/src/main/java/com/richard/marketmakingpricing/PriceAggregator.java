package com.richard.marketmakingpricing;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

public class PriceAggregator {
    private final NavigableMap<Double, Map<String, Integer>> bids = new ConcurrentSkipListMap<>(Collections.reverseOrder());
    private final NavigableMap<Double, Map<String, Integer>> asks = new ConcurrentSkipListMap<>();
    private final Map<String, LPQuoteRecord> lpRegistry = new HashMap<>();
    
    // Now using the external interface
    private MarketUpdateListener listener;

    public void setListener(MarketUpdateListener listener) {
        this.listener = listener;
    }

    public synchronized void onUpdate(String lpId, double bid, int bidSize, double ask, int askSize) {
        LPQuoteRecord old = lpRegistry.get(lpId);
        if (old != null) {
            removeLiquidity(bids, old.lastBid, lpId);
            removeLiquidity(asks, old.lastAsk, lpId);
        }

        bids.computeIfAbsent(bid, k -> new HashMap<>()).put(lpId, bidSize);
        asks.computeIfAbsent(ask, k -> new HashMap<>()).put(lpId, askSize);
        lpRegistry.put(lpId, new LPQuoteRecord(bid, ask));

        triggerUpdate();
    }

    private void triggerUpdate() {
        if (listener == null || bids.isEmpty() || asks.isEmpty()) return;

        double bb = bids.firstKey();
        int bs = bids.get(bb).values().stream().mapToInt(i -> i).sum();
        
        double ba = asks.firstKey();
        int as = asks.get(ba).values().stream().mapToInt(i -> i).sum();

        listener.onBookUpdate(bb, bs, ba, as);
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

        for (Map.Entry<Double, Map<String, Integer>> entry : side.entrySet()) {
            double price = entry.getKey();
            int volumeAtLevel = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();

            int take = Math.min(remaining, volumeAtLevel);
            totalCost += (take * price);
            remaining -= take;

            if (remaining <= 0) break;
        }
        return (targetQty - remaining == 0) ? (totalCost / targetQty) : (totalCost / (targetQty - remaining));
    }

    private static class LPQuoteRecord {
        double lastBid, lastAsk;
        LPQuoteRecord(double b, double a) { lastBid = b; lastAsk = a; }
    }
}