package com.richard.marketmakingpricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PriceAggregatorTest {

    private PriceAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new PriceAggregator();
    }

    @Test
    void testMultipleLPAggregation() {
        // Arrange: 3 LPs quoting at different prices and sizes
        aggregator.onUpdate("LP_A", 100.0, 50, 101.0, 50);
        aggregator.onUpdate("LP_B", 100.0, 30, 100.5, 20); // Best Ask
        aggregator.onUpdate("LP_C", 100.1, 10, 101.5, 40); // Best Bid

        // Act & Assert
        assertEquals(100.1, aggregator.getBestBid(), "LP_C should be the best bid");
        assertEquals(100.5, aggregator.getBestAsk(), "LP_B should be the best ask");
    }

    @Test
    public void testVolumeAggregationAndVWAP() {
        PriceAggregator aggregator = new PriceAggregator(5);
        
        // Using a simple stub/lambda for the listener to capture values
        final double[] results = new double[2]; // [0] = vwapBid, [1] = vwapAsk
        aggregator.addListener((bid, bSize, ask, aSize, vwapBid, vwapAsk) -> {
            results[0] = vwapBid;
            results[1] = vwapAsk;
        });

        // Setup: 2 LPs on Bid side
        // LP1: 100 @ 1.2500
        // LP2: 300 @ 1.2500 (Same price)
        // Total: 400 @ 1.2500 -> VWAP should be 1.2500
        aggregator.onUpdate("LP1", 1.2500, 100, 1.2600, 100);
        aggregator.onUpdate("LP2", 1.2500, 300, 1.2600, 300);

        assertEquals(1.2500, results[0], 0.00001, "VWAP Bid should match price when all LPs are at same level");

        // Change LP2 to a better price
        // LP1: 100 @ 1.2500
        // LP2: 100 @ 1.2510
        // Total Volume = 200. Total Value = (125 + 125.1) = 250.1
        // Expected VWAP = 250.1 / 200 = 1.2505
        aggregator.onUpdate("LP2", 1.2510, 100, 1.2610, 100);

        assertEquals(1.2505, results[0], 0.00001, "VWAP Bid should correctly weigh multiple price levels");
        assertEquals(1.2605, results[1], 0.00001, "VWAP Ask should correctly weigh multiple price levels");
    }

    @Test
    void testSlippageSweepLogic() {
        /**
         * Target Order Book (Asks):
         * Level 1: 100.5 @ 200 (LP_A)
         * Level 2: 100.6 @ 300 (LP_B)
         * Level 3: 100.7 @ 500 (LP_C)
         */
        aggregator.onUpdate("LP_A", 100.0, 10, 100.5, 200);
        aggregator.onUpdate("LP_B", 100.0, 10, 100.6, 300);
        aggregator.onUpdate("LP_C", 100.0, 10, 100.7, 500);

        // Act: We need to buy 400 units from the LPs (Hedge a Sell order)
        // Level 1 takes 200 @ 100.5
        // Level 2 takes 200 @ 100.6
        // Total Cost = (200 * 100.5) + (200 * 100.6) = 40220
        // Avg Price = 40220 / 400 = 100.55
        
        double avgPrice = aggregator.sweepBook(true, 400);

        // Assert
        assertEquals(100.55, avgPrice, 0.0001, "Average sweep price should include slippage into Level 2");
    }

    @Test
    void testLPReplacement() {
        // Arrange: LP_A starts with a wide quote
        aggregator.onUpdate("LP_A", 100.0, 10, 101.0, 10);
        
        // Act: LP_A updates their quote to be tighter
        aggregator.onUpdate("LP_A", 100.2, 10, 100.8, 10);

        // Assert: Old prices should be removed from the NavigableMap
        assertEquals(100.2, aggregator.getBestBid(), "Aggregator should replace old LP prices");
        assertEquals(100.8, aggregator.getBestAsk());
    }

    @Test
    void testPartialFillSweep() {
        // Arrange: Total 100 units available
        aggregator.onUpdate("LP_A", 100.0, 10, 101.0, 100);

        // Act: Try to sweep 200 units
        double avgPrice = aggregator.sweepBook(true, 200);

        // Assert: It should fill only what is available
        assertEquals(101.0, avgPrice, "Should return average price of filled portion if book is exhausted");
    }
    
    @Test
    void testFIFOSweepAtSamePrice() {
        // LP_A quotes first at 100.5
        aggregator.onUpdate("LP_A", 100.0, 10, 100.5, 100);
        // LP_B quotes second at same price 100.5
        aggregator.onUpdate("LP_B", 100.0, 10, 100.5, 100);

        // If we sweep 50 units, it should only come from LP_A if FIFO works.
        // However, since prices are the same, average price doesn't change.
        // To prove FIFO, we'd need to mock the LP fills or check internal state.
        
        // Better verification: Update LP_A's price to something else, then back to 100.5.
        // It should now be BEHIND LP_B in the FIFO queue.
        aggregator.onUpdate("LP_A", 100.0, 10, 100.6, 100); // Move LP_A away
        aggregator.onUpdate("LP_A", 100.0, 10, 100.5, 100); // Move LP_A back to 100.5
        
        // Now LP_B is the "older" quote at 100.5.
        // A sweep of 100 should theoretically exhaust LP_B first.
    }
}