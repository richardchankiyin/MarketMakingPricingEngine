package com.richard.marketmakingpricing;

public interface PricingListener {
    /**
     * Called whenever a new quote is calculated.
     * All parameters are passed as primitives to ensure zero-GC and consistency.
     */
    void onQuoteUpdate(double bid, int bSize, double ask, int aSize, double idealBid, double idealAsk);
}