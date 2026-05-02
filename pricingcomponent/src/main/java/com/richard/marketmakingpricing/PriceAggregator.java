package com.richard.marketmakingpricing;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PriceAggregator maintains a consolidated view of liquidity from multiple LPs.
 * It decouples light-weight summary broadcasts from heavy-weight full-book
 * snapshots to optimize for both the Pricing Engine and the OMS.
 */
public class PriceAggregator {
	private static final Logger log = LoggerFactory.getLogger(PriceAggregator.class);
	// MarketUpdateListeners receive L1 + VWAP (Low bandwidth)
	private final List<MarketUpdateListener> marketListeners = new CopyOnWriteArrayList<>();
	// OrderBookUpdateListeners receive full deep-cloned snapshots (High bandwidth)
	private final List<OrderBookUpdateListener> bookListeners = new CopyOnWriteArrayList<>();

	// ConcurrentSkipListMap ensures price-sorting (Natural order for asks, reverse
	// for bids)
	private final NavigableMap<Double, Map<String, Integer>> bids = new ConcurrentSkipListMap<>(
			Collections.reverseOrder());
	private final NavigableMap<Double, Map<String, Integer>> asks = new ConcurrentSkipListMap<>();

	// Registry to track the "previous" state of an LP to facilitate liquidity
	// removal
	private final Map<String, LPQuoteRecord> lpRegistry;

	private final ReentrantLock lock = new ReentrantLock();
	private final int lpCount;
	private static final int ONUPDATETRYLOCK_MS = 5;

	public PriceAggregator(int lpCount) {
		this.lpCount = lpCount;
		this.lpRegistry = new ConcurrentHashMap<>(lpCount);
	}

	public void addMarketListener(MarketUpdateListener listener) {
		this.marketListeners.add(listener);
	}

	public void addBookListener(OrderBookUpdateListener listener) {
		this.bookListeners.add(listener);
	}

	/**
	 * Entry point for new LP ticks. Handles the "Remove-then-Add" logic to maintain
	 * the book.
	 */
	public void onUpdate(String lpId, double bid, int bidSize, double ask, int askSize) {
		long before = System.nanoTime();
		LPQuoteRecord record = lpRegistry.computeIfAbsent(lpId, k -> new LPQuoteRecord());

		try {
			if (lock.tryLock(ONUPDATETRYLOCK_MS, TimeUnit.MILLISECONDS)) {
				try {
					// 1. Always attempt removal of the OLD price levels first
					if (record.hasValidPrices()) {
						removeLiquidity(bids, record.getLastBid(), lpId);
						removeLiquidity(asks, record.getLastAsk(), lpId);
					}

					// 2. IMPORTANT: Even if the price is the same, remove it from the
					// current level to force a FIFO position reset
					bids.getOrDefault(bid, Collections.emptyMap()).remove(lpId);
					asks.getOrDefault(ask, Collections.emptyMap()).remove(lpId);

					// 3. Now re-insert (This guarantees LP goes to the end of the LinkedHashMap)
					bids.computeIfAbsent(bid, k -> new LinkedHashMap<>(lpCount)).put(lpId, bidSize);
					asks.computeIfAbsent(ask, k -> new LinkedHashMap<>(lpCount)).put(lpId, askSize);

					record.update(bid, bidSize, ask, askSize);
					triggerParallelUpdates();
				} finally {
					lock.unlock();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		log.info("time diff in μs: {}", System.nanoTime() - before);
	}

	/**
	 * Dispatches updates to listeners in parallel.
	 * Uses join() to ensure the method is synchronous, maintaining sequential data
	 * consistency.
	 */
	private void triggerParallelUpdates() {
		if (bids.isEmpty() || asks.isEmpty())
			return;
		CompletableFuture<Void> marketTask = CompletableFuture.runAsync(this::broadcastSummary);
		CompletableFuture<Void> bookTask = CompletableFuture.runAsync(this::broadcastFullBook);
		// Blocking wait ensures the next LP tick won't overlap with current listener
		// processing
		CompletableFuture.allOf(marketTask, bookTask).join();
	}

	/**
	 * Calculates L1 (Best Bid/Ask) and VWAP for the Pricing Engine.
	 */
	private void broadcastSummary() {
		if (marketListeners.isEmpty())
			return;

		// Bid Side Metrics
		double bestBid = bids.firstKey();
		int topBidSize = 0;
		double totalBidValue = 0;
		long totalBidVolume = 0;

		for (Map.Entry<Double, Map<String, Integer>> entry : bids.entrySet()) {
			double price = entry.getKey();
			for (Integer size : entry.getValue().values()) {
				if (size != null) {
					if (price == bestBid)
						topBidSize += size;
					totalBidValue += (price * size);
					totalBidVolume += size;
				}
			}
		}
		double vwapBid = totalBidVolume == 0 ? 0 : totalBidValue / totalBidVolume;

		// Ask Side Metrics
		double bestAsk = asks.firstKey();
		int topAskSize = 0;
		double totalAskValue = 0;
		long totalAskVolume = 0;

		for (Map.Entry<Double, Map<String, Integer>> entry : asks.entrySet()) {
			double price = entry.getKey();
			for (Integer size : entry.getValue().values()) {
				if (size != null) {
					if (price == bestAsk)
						topAskSize += size;
					totalAskValue += (price * size);
					totalAskVolume += size;
				}
			}
		}
		double vwapAsk = totalAskVolume == 0 ? 0 : totalAskValue / totalAskVolume;

		for (MarketUpdateListener l : marketListeners) {
			l.onSummaryUpdate(bestBid, topBidSize, bestAsk, topAskSize, vwapBid, vwapAsk);
		}
	}

	/**
	 * Performs a deep-copy of the book for the OMS/SOR components. This prevents
	 * ConcurrentModificationExceptions when listeners iterate the maps.
	 */
	private void broadcastFullBook() {
		if (bookListeners.isEmpty())
			return;

		NavigableMap<Double, Map<String, Integer>> bidsCopy = cloneBook(bids);
		NavigableMap<Double, Map<String, Integer>> asksCopy = cloneBook(asks);

		for (OrderBookUpdateListener l : bookListeners) {
			l.onFullBookUpdate(bidsCopy, asksCopy);
		}
	}

	private NavigableMap<Double, Map<String, Integer>> cloneBook(NavigableMap<Double, Map<String, Integer>> original) {
		NavigableMap<Double, Map<String, Integer>> copy = new TreeMap<>(original.comparator());
		for (Map.Entry<Double, Map<String, Integer>> entry : original.entrySet()) {
			copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
		}
		return copy;
	}

	private void removeLiquidity(NavigableMap<Double, Map<String, Integer>> book, double price, String lpId) {
		Map<String, Integer> levels = book.get(price);
		if (levels != null) {
			levels.remove(lpId);
			if (levels.isEmpty())
				book.remove(price);
		}
	}

	private static class LPQuoteRecord {
		private double lastBid = -1, lastAsk = -1;

		private void update(double b, int bs, double a, int as) {
			this.lastBid = b;
			this.lastAsk = a;
		}

		public double getLastBid() {
			return lastBid;
		}

		public double getLastAsk() {
			return lastAsk;
		}

		private boolean hasValidPrices() {
			return lastBid != -1;
		}
	}
}