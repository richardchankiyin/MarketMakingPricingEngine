package com.richard.marketmakingpricing;


@FunctionalInterface
public interface LPQuoteListener {
    void onLPUpdate(String lpId, double refPrice, double bid, int bSize, double ask, int aSize);
}