package com.richard.marketmakingpricing.oms;

public abstract class Order {
    private final long clOrdID;
    private final String senderCompID;
    private final String targetCompID;
    private final boolean isBuy; 
    private final int orderQty;
    private final double price;
    private final long transactTime;

    protected Order(long clOrdID, String sender, String target, boolean isBuy, int qty, double price, long transactTime) {
        this.clOrdID = clOrdID;
        this.senderCompID = sender;
        this.targetCompID = target;
        this.isBuy = isBuy;
        this.orderQty = qty;
        this.price = price;
        this.transactTime = transactTime;
    }

    // Accessors
    public boolean isSideBuy() { return this.isBuy; } // Added per request
    
    public long getClOrdID() { return clOrdID; }
    public String getSenderCompID() { return senderCompID; }
    public String getTargetCompID() { return targetCompID; }
    public String getSide() { return isBuy ? "1" : "2"; } // FIX Tag 54
    public int getOrderQty() { return orderQty; }
    public double getPrice() { return price; }
    public long getTransactTime() { return transactTime; }

    // Static FIX Tags
    public String getOrdType() { return "2"; }      // Limit
    public String getTimeInForce() { return "4"; }  // FOK
    
    public String toString() {
    	return String.format("Order - [clOrdId:%s|senderCompID:%s|targetCompID:%s|isBuy:%s|orderQty:%s|price:%f|transactTime:%d]",
    			this.clOrdID, this.senderCompID, this.targetCompID, this.isBuy, this.orderQty, this.price, this.transactTime);
    }
}