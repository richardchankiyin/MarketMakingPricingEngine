package com.richard.marketmakingpricing;

/**
 * Clean interface to decouple the Aggregator from the Pricing Engine.
 */
public interface MarketUpdateListener {	
    
    /**
     * @param bid Best bid price
     * @param bSize Total size at best bid
     * @param ask Best ask price
     * @param aSize Total size at best ask
     * @param vwapBid Volume-weighted average price of all bids
     * @param vwapAsk Volume-weighted average price of all asks
     */
    void onBookUpdate(double bid, int bSize, double ask, int aSize, double vwapBid, double vwapAsk);
}
