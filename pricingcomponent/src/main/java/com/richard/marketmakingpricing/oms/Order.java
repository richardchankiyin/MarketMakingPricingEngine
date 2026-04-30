package com.richard.marketmakingpricing.oms;

public abstract class Order {
    private final long clOrdID;
    private final String senderCompID;
    private final String targetCompID;
    private final String side;
    private final int orderQty;
    private final double price;
    private final long transactTime;

    protected Order(long clOrdID, String sender, String target, String side, int qty, double price, long transactTime) {
        this.clOrdID = clOrdID;
        this.senderCompID = sender;
        this.targetCompID = target;
        this.side = side;
        this.orderQty = qty;
        this.price = price;
        this.transactTime = transactTime;
    }

    // Getters
    public long getClOrdID() { return clOrdID; }
    public String getSenderCompID() { return senderCompID; }
    public String getTargetCompID() { return targetCompID; }
    public String getSide() { return side; }
    public int getOrderQty() { return orderQty; }
    public double getPrice() { return price; }
    public long getTransactTime() { return transactTime; }

    // Logic-based Getters (No instance variables needed)
    public String getOrdType() { return "2"; }      // Limit
    public String getTimeInForce() { return "4"; }  // FOK
}