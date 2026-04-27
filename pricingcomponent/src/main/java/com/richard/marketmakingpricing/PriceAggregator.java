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
    private static final int ONUPDATETRYLOCK_MS=5;

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
            if (lock.tryLock(ONUPDATETRYLOCK_MS, TimeUnit.MILLISECONDS)) {
                try {
                    if (record.hasValidPrices()) {
                        removeLiquidity(bids, record.getLastBid(), lpId);
                        removeLiquidity(asks, record.getLastAsk(), lpId);
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

        // --- BID SIDE PASS ---
        double bestBid = bids.firstKey();
        int topBidSize = 0;
        double totalBidValue = 0;
        long totalBidVolume = 0;

        for (Map.Entry<Double, Map<String, Integer>> entry : bids.entrySet()) {
            double price = entry.getKey();
            int levelSize = 0;
            for (Integer size : entry.getValue().values()) {
                if (size != null) {
                    int s = size.intValue();
                    levelSize += s;
                    totalBidValue += (price * s);
                    totalBidVolume += s;
                }
            }
            // Capture top-of-book size on the first iteration
            if (price == bestBid) topBidSize = levelSize;
        }
        double vwapBid = totalBidVolume == 0 ? 0 : totalBidValue / totalBidVolume;

        // --- ASK SIDE PASS ---
        double bestAsk = asks.firstKey();
        int topAskSize = 0;
        double totalAskValue = 0;
        long totalAskVolume = 0;

        for (Map.Entry<Double, Map<String, Integer>> entry : asks.entrySet()) {
            double price = entry.getKey();
            int levelSize = 0;
            for (Integer size : entry.getValue().values()) {
                if (size != null) {
                    int s = size.intValue();
                    levelSize += s;
                    totalAskValue += (price * s);
                    totalAskVolume += s;
                }
            }
            if (price == bestAsk) topAskSize = levelSize;
        }
        double vwapAsk = totalAskVolume == 0 ? 0 : totalAskValue / totalAskVolume;

        // Broadcast everything in one go
        listener.onBookUpdate(bestBid, topBidSize, bestAsk, topAskSize, vwapBid, vwapAsk);
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
        private double lastBid = -1, lastAsk = -1;
        private int lastBidSize, lastAskSize;
        private double prevBid, prevAsk;
        private int prevBidSize, prevAskSize;

        private void update(double b, int bs, double a, int as) {
            this.prevBid = this.lastBid;
            this.prevBidSize = this.lastBidSize;
            this.prevAsk = this.lastAsk;
            this.prevAskSize = this.lastAskSize;

            this.lastBid = b;
            this.lastBidSize = bs;
            this.lastAsk = a;
            this.lastAskSize = as;
        }
        
        
        
        public double getLastBid() {
			return lastBid;
		}
		public double getLastAsk() {
			return lastAsk;
		}

		@SuppressWarnings("unused")
		public int getLastBidSize() {
			return lastBidSize;
		}
		@SuppressWarnings("unused")
		public int getLastAskSize() {
			return lastAskSize;
		}
		@SuppressWarnings("unused")
		public double getPrevBid() {
			return prevBid;
		}
		@SuppressWarnings("unused")
		public double getPrevAsk() {
			return prevAsk;
		}
		@SuppressWarnings("unused")
		public int getPrevBidSize() {
			return prevBidSize;
		}
		@SuppressWarnings("unused")
		public int getPrevAskSize() {
			return prevAskSize;
		}
		private boolean hasValidPrices() { return lastBid != -1; }
    }

    // Getters for Test/Audit
    public double getBestBid() { return bids.isEmpty() ? 0.0 : bids.firstKey(); }
    public double getBestAsk() { return asks.isEmpty() ? Double.MAX_VALUE : asks.firstKey(); }
}