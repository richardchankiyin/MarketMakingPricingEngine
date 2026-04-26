package com.richard.marketmakingpricing;

public class Quote {
    public double bidPrice;
    public int bidSize;
    public double askPrice;
    public int askSize;
    public long timestamp;

    public void update(double bp, int bs, double ap, int as, long ts) {
        this.bidPrice = bp;
        this.bidSize = bs;
        this.askPrice = ap;
        this.askSize = as;
        this.timestamp = ts;
    }
}