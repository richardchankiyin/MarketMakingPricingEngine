package com.richard.marketmakingpricing;

public class AggregatedBook {
    private final double bestBid;
    private final double bestAsk;

    public AggregatedBook(double bestBid, double bestAsk) {
        this.bestBid = bestBid;
        this.bestAsk = bestAsk;
    }

    public double getBestBid() { return bestBid; }
    public double getBestAsk() { return bestAsk; }
}