package com.richard.marketmakingpricing;

/**
 * Interface for components that need to react to changes in market sentiment/alpha.
 */
public interface SignalListener {
    /**
     * Called when the alpha signal crosses a significance threshold.
     * * @param newSignal A value between -1.0 (strongly bearish) and 1.0 (strongly bullish).
     */
    void onSignalChange(double newSignal);
}