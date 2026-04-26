package com.richard.marketmakingpricing;

/**
 * Clean interface to decouple the Aggregator from the Pricing Engine.
 */
public interface MarketUpdateListener {
    void onBookUpdate(double bid, int bSize, double ask, int aSize);
}
