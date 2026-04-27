package com.richard.marketmakingpricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PricingEngineTest {

    private PricingEngine engine;
    private SignalEmitter mockSignalEmitter;
    private PriceAggregator mockAggregator;

    // Test Constants
    private final double TICK = 0.01;
    private final double MARGIN = 0.02;
    private final double SKEW = 0.20;
    private final int Q_SIZE = 500;
    private static final double DELTA = 1e-9;

    @BeforeEach
    void setUp() {
        mockSignalEmitter = mock(SignalEmitter.class);
        mockAggregator = mock(PriceAggregator.class);
        
        engine = new PricingEngine(
            mockAggregator, 
            mockSignalEmitter, 
            TICK, 
            MARGIN, 
            SKEW, 
            Q_SIZE
        );
    }

    @Test
    void testListenerCallbackConsistency() {
        // GIVEN: A listener that captures the update
        // We use an array to capture values inside the lambda
        double[] results = new double[4]; // [bid, ask, iBid, iAsk]
        
        engine.addListener((bid, bSize, ask, aSize, iBid, iAsk) -> {
            results[0] = bid;
            results[1] = ask;
            results[2] = iBid;
            results[3] = iAsk;
        });

        // WHEN: Market is 100.00 / 100.10 and Signal is Bearish (-1.0)
        engine.onBookUpdate(100.00, 1000, 100.10, 1000, 100.00, 100.10);
        engine.onSignalChange(-1.0);

        // THEN: Verify that the pushed values match the engine state
        // Ideal Mid: 100.05 - 0.20 = 99.85
        // Ideal Bid: 99.85 - 0.01 = 99.84 (Rounded)
        // Final Bid: Min(99.84, 100.00 - 0.01) = 99.84
        assertEquals(99.84, results[0], DELTA, "Pushed Bid should be 99.84");
        assertEquals(99.84, results[2], DELTA, "Pushed Ideal Bid should be 99.84");
        
        // Final Ask: Max(IdealAsk 99.86, Floor 100.11) = 100.11
        assertEquals(100.11, results[1], DELTA, "Pushed Ask should be pinned to floor 100.11");
    }

    @Test
    void testProfitabilityInBullMarket() {
        // GIVEN: Market 10.00 / 10.02 (Very tight)
        double lpBid = 10.00;
        double lpAsk = 10.02;
        
        // Signal is Bullish (+1.0)
        engine.onBookUpdate(lpBid, 100, lpAsk, 100, lpBid, lpAsk);
        engine.onSignalChange(1.0);

        // THEN: We must still be able to hedge profitably
        // To hedge a SELL (myAsk), we buy from LP at lpAsk. So myAsk must be > lpAsk.
        assertTrue(engine.getMyAsk() >= lpAsk + TICK - DELTA, 
            String.format("Our Ask (%.3f) must be higher than LP Ask (%.3f)", engine.getMyAsk(), lpAsk));
            
        // To hedge a BUY (myBid), we sell to LP at lpBid. So myBid must be < lpBid.
        assertTrue(engine.getMyBid() <= lpBid - TICK + DELTA, 
            String.format("Our Bid (%.3f) must be lower than LP Bid (%.3f)", engine.getMyBid(), lpBid));
    }

    @Test
    void testSizePropagation() {
        // WHEN: Any update occurs
        engine.onBookUpdate(100.00, 100, 100.10, 100, 100.00, 100.10);

        // THEN: The fixed quote size should be present
        assertEquals(Q_SIZE, engine.getMyBidSize());
        assertEquals(Q_SIZE, engine.getMyAskSize());
    }

    @Test
    void testSafetyGuardCrossedQuotes() {
        // GIVEN: A strange situation where logic might cross quotes
        // Mid 100.05. Skew 0.0. Margin 0.20 (wider than LP spread)
        PricingEngine wideEngine = new PricingEngine(mockAggregator, mockSignalEmitter, 0.01, 0.20, 0.0, 100);
        
        wideEngine.onBookUpdate(100.00, 100, 100.10, 100, 100.00, 100.10);
        
        // THEN: Bid must always be less than Ask
        assertTrue(wideEngine.getMyBid() < wideEngine.getMyAsk(), "Quotes must never be crossed");
        
        // In this specific case:
        // Ideal Bid = 100.05 - 0.10 = 99.95. Clamped = Min(99.95, 99.99) = 99.95
        // Ideal Ask = 100.05 + 0.10 = 100.15. Clamped = Max(100.15, 100.11) = 100.15
        assertEquals(99.95, wideEngine.getMyBid(), DELTA);
        assertEquals(100.15, wideEngine.getMyAsk(), DELTA);
    }
}