package com.richard.marketmakingpricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceAggregatorTest {

    private PriceAggregator aggregator;

    @BeforeEach
    void setUp() {
        // Initialize with capacity for 5 LPs
        aggregator = new PriceAggregator(5);
    }

    /**
     * Test L1 and VWAP summary broadcast logic.
     * Logic: 2 LPs on Bid side. 
     * LP1: 100 @ 1.2500, LP2: 300 @ 1.2500. Total: 400 @ 1.2500.
     * Expected VWAP = 1.2500
     */
    @Test
    void testMarketSummaryBroadcast() {
        AtomicReference<Double> vwapBidRef = new AtomicReference<>(0.0);
        
        aggregator.addMarketListener((bestBid, topBidSize, bestAsk, topAskSize, vwapBid, vwapAsk) -> {
            vwapBidRef.set(vwapBid);
        });

        aggregator.onUpdate("LP1", 1.2500, 100, 1.2600, 100);
        aggregator.onUpdate("LP2", 1.2500, 300, 1.2600, 300);
        
        assertEquals(1.2500, vwapBidRef.get(), 0.00001, "VWAP Bid should match price when all LPs are at same level");
    }

    /**
     * Test Aggregated Book logic for OMS.
     * Verifies that multiple LPs at the same price are stored in a LinkedHashMap 
     * inside the specific price level of the NavigableMap.
     */
    @Test
    void testFullBookAggregation() {
        AtomicReference<NavigableMap<Double, Map<String, Integer>>> capturedBids = new AtomicReference<>();

        aggregator.addBookListener((bids, asks) -> {
            capturedBids.set(bids);
        });

        // Add liquidity at same price from two different LPs
        aggregator.onUpdate("LP_1", 100.0, 10, 101.0, 10);
        aggregator.onUpdate("LP_2", 100.0, 20, 101.0, 20);

        NavigableMap<Double, Map<String, Integer>> book = capturedBids.get();
        
        assertTrue(book.containsKey(100.0), "Price level 100.0 must exist");
        assertEquals(10, book.get(100.0).get("LP_1"), "LP_1 size should be captured");
        assertEquals(20, book.get(100.0).get("LP_2"), "LP_2 size should be aggregated at same level");
    }

    /**
     * Test Liquidity Removal / Ghost Pricing.
     * Ensures that when an LP moves a quote, the old price entry is purged.
     */
    @Test
    void testLiquidityRemovalOnUpdate() {
        // Initial state at 100.0
        aggregator.onUpdate("LP_1", 100.0, 10, 101.0, 10);
        
        // LP_1 moves to 99.0
        aggregator.onUpdate("LP_1", 99.0, 10, 102.0, 10);
        
        aggregator.addBookListener((bids, asks) -> {
            assertFalse(bids.containsKey(100.0), "Old price level (100.0) should have been removed");
            assertTrue(bids.containsKey(99.0), "New price level (99.0) should exist");
        });
        
        // Trigger dummy update to fire listener
        aggregator.onUpdate("LP_2", 98.0, 5, 103.0, 5);
    }

    /**
     * FIFO Priority for the Bid Side.
     * Verifies that the oldest quote at a specific price level is the first in the map.
     */
    @Test
    void testFIFOPriorityBid() {
        AtomicReference<String> firstLpDetected = new AtomicReference<>("");
        aggregator.addBookListener((bids, asks) -> {
            Map<String, Integer> level = bids.get(100.0);
            if (level != null && level.size() > 1) {
                firstLpDetected.set(level.keySet().iterator().next());
            }
        });

        // LP_A quotes first, then LP_B at 100.0
        aggregator.onUpdate("LP_A", 100.0, 10, 101.0, 10);
        aggregator.onUpdate("LP_B", 100.0, 20, 101.0, 20);

        // Move LP_A away and bring it back. It should now be BEHIND LP_B.
        aggregator.onUpdate("LP_A", 99.0, 10, 102.0, 10);
        aggregator.onUpdate("LP_A", 100.0, 10, 101.0, 10);

        // Dummy update to trigger broadcast
        aggregator.onUpdate("Trigger", 1.0, 1, 200.0, 1);

        assertEquals("LP_B", firstLpDetected.get(), "On Bid side, LP_B should be first (older) at 100.0");
    }

    /**
     * Breakdown: FIFO Priority for the Ask Side.
     */
    @Test
    void testFIFOPriorityAsk() {
        AtomicReference<String> firstLpDetected = new AtomicReference<>("");
        aggregator.addBookListener((bids, asks) -> {
            Map<String, Integer> level = asks.get(101.0);
            if (level != null && level.size() > 1) {
                firstLpDetected.set(level.keySet().iterator().next());
            }
        });

        aggregator.onUpdate("LP_A", 100.0, 10, 101.0, 10);
        aggregator.onUpdate("LP_B", 100.0, 20, 101.0, 20);

        // Move LP_A away and bring it back.
        aggregator.onUpdate("LP_A", 98.0, 10, 105.0, 10);
        aggregator.onUpdate("LP_A", 100.0, 10, 101.0, 10);

        aggregator.onUpdate("Trigger", 1.0, 1, 200.0, 1);

        assertEquals("LP_B", firstLpDetected.get(), "On Ask side, LP_B should be first (older) at 101.0");
    }

    /**
     * Multi-threading Stress Test.
     * Simulates 5 LPs updating simultaneously. 
     * Verifies that the aggregator lock handles contention without dropping data.
     */
    @Test
    void testMultiThreadedUpdates() throws InterruptedException {
        int threadCount = 5;
        java.util.concurrent.ExecutorService service = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

        // We expect the final vwap to be exactly 100.0 if all threads succeed
        aggregator.addMarketListener((bestBid, topBidSize, bestAsk, topAskSize, vwapBid, vwapAsk) -> {
            // Summary updates will fire frequently
        });

        for (int i = 0; i < threadCount; i++) {
            final String lpId = "LP_" + i;
            service.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        aggregator.onUpdate(lpId, 100.0, 10, 101.0, 10);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        service.shutdown();

        // Verify the final state is consistent
        aggregator.addBookListener((bids, asks) -> {
            Map<String, Integer> level = bids.get(100.0);
            assertNotNull(level);
            assertEquals(threadCount, level.size(), "Should have aggregated exactly " + threadCount + " LPs");
        });
        
        aggregator.onUpdate("FinalTrigger", 1.0, 1, 200.0, 1);
    }
}