package com.richard.marketmakingpricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SignalEmitterTest {

    private SignalEmitter signalEmitter;
    private SignalListener mockListener;

    @BeforeEach
    void setUp() {
        signalEmitter = new SignalEmitter();
        mockListener = mock(SignalListener.class);
        signalEmitter.addListener(mockListener);
    }

    @Test
    void testBidAggressionGeneratesBearishSignal() {
        // Market Mid = (100.0 + 101.0) / 2 = 100.5
        // Bid VWAP is very close to mid (100.48) -> Urgency = 0.02
        // Ask VWAP is far (101.50) -> Urgency = 1.00
        // Expect negative signal because Bid is aggressive (Keen to Sell)
        
        signalEmitter.onSummaryUpdate(100.0, 100, 101.0, 100, 100.48, 101.50);

        double signal = signalEmitter.getCurrentSignal();
        assertTrue(signal < 0, "Signal should be negative for bid aggression. Actual: " + signal);
        verify(mockListener, atLeastOnce()).onSignalChange(anyDouble());
    }

    @Test
    void testAskAggressionGeneratesBullishSignal() {
        // Market Mid = 100.5
        // Bid VWAP is far (99.50) -> Urgency = 1.00
        // Ask VWAP is very close (100.52) -> Urgency = 0.02
        // Expect positive signal (Keen to Buy)

        signalEmitter.onSummaryUpdate(100.0, 100, 101.0, 100, 99.50, 100.52);

        double signal = signalEmitter.getCurrentSignal();
        assertTrue(signal > 0, "Signal should be positive for ask aggression. Actual: " + signal);
        verify(mockListener, atLeastOnce()).onSignalChange(anyDouble());
    }

    @Test
    void testNeutralMarketGeneratesZeroSignal() {
        // Mid = 100.5
        // Both VWAPs are exactly 0.5 away from Mid
        signalEmitter.onSummaryUpdate(100.0, 100, 101.0, 100, 100.0, 101.0);

        assertEquals(0.0, signalEmitter.getCurrentSignal(), 0.0001);
    }

    @Test
    void testThresholdPreventsFlickering() {
        // Baseline update
        signalEmitter.onSummaryUpdate(100.0, 100, 101.0, 100, 100.2, 100.8);
        reset(mockListener);

        // Update with an extremely small change in VWAP
        signalEmitter.onSummaryUpdate(100.0, 100, 101.0, 100, 100.200001, 100.8);

        // Signal should not have updated/emitted
        verify(mockListener, never()).onSignalChange(anyDouble());
    }
    
    @Test
    void testCustomThresholdFiltering() {
        // GIVEN: A high threshold of 0.5
        SignalEmitter emitter = new SignalEmitter(0.5);
        SignalListener mockListener = mock(SignalListener.class);
        emitter.addListener(mockListener);

        // Baseline: Signal is 0.0
        emitter.onSummaryUpdate(100.0, 100, 101.0, 100, 100.0, 101.0);
        reset(mockListener);

        // Update: Aggressive Bid (should generate roughly -0.2 signal)
        // Mid 100.5. BidUrgency = 100.5 - 100.48 = 0.02. AskUrgency = 101.0 - 100.5 = 0.5.
        // RawSkew = (0.5 - 0.02)/100.5 = 0.0047. Result * 1000 = -4.7 (Clamped to -1.0)
        // Since -1.0 is > 0.5 threshold away from 0.0, this SHOULD fire.
        emitter.onSummaryUpdate(100.0, 100, 101.0, 100, 100.48, 101.0);
        verify(mockListener, times(1)).onSignalChange(anyDouble());
        
        reset(mockListener);

        // Update: Tiny change that results in a signal very close to -1.0 (e.g. -0.99)
        // Delta from previous (-1.0) is 0.01, which is < 0.5 threshold.
        emitter.onSummaryUpdate(100.0, 100, 101.0, 100, 100.479, 101.0);
        
        // THEN: Should NOT fire
        verify(mockListener, never()).onSignalChange(anyDouble());
    }

    @Test
    void testDefaultThreshold() {
        SignalEmitter emitter = new SignalEmitter(); // Uses 0.001
        SignalListener mockListener = mock(SignalListener.class);
        emitter.addListener(mockListener);

        // mid = 100.5
        // bidUrgency = 100.5 - 100.4 = 0.1
        // askUrgency = 101.0 - 100.5 = 0.5
        // This will create a non-zero signal!
        emitter.onSummaryUpdate(100, 10, 101, 10, 100.4, 101.0);
        
        verify(mockListener, atLeastOnce()).onSignalChange(anyDouble());
        assertTrue(emitter.getCurrentSignal() != 0.0);
    }
}