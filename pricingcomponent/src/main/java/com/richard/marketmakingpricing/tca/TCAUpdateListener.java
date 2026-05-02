package com.richard.marketmakingpricing.tca;

public interface TCAUpdateListener {
    /**
     * Called on every order update to provide both client-specific 
     * and firm-wide performance data.
     */
    void onTCAUpdate(ClientMetrics clientMetrics, FirmMetrics firmMetrics);
}