package com.richard.marketmakingpricing.oms;

/**
 * Disruptor Event for incoming orders.
 * Pre-allocated in the RingBuffer to minimize GC pressure.
 */
public class OrderEntryEvent {
    // Tag 11: Unique identifier from the Taker
    private long parentId;
    
    // Tag 49: The Taker Client ID (dynamic routing)
    private String senderId;
    
    // Tag 54: "1" for Buy, "2" for Sell, or "BUY"/"SELL"
    private String side;
    
    // Tag 38: Quantity
    private int qty;
    
    // Tag 44: Limit Price
    private double limit;
    
    // Tag 60: Transact Time
    private long transactTime;

    /**
     * Helper to clear the event data when being recycled in the RingBuffer.
     */
    public void reset() {
        this.parentId = 0;
        this.senderId = null;
        this.side = null;
        this.qty = 0;
        this.limit = 0.0;
        this.transactTime = 0L;
    }
    
    // Getters and Setters
    public long getParentId() { return parentId; }
    public void setParentId(long parentId) { this.parentId = parentId; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }

    public double getLimit() { return limit; }
    public void setLimit(double limit) { this.limit = limit; }
    
    public long getTransactTime() { return transactTime; }
    public void setTransactTime(long transactTime) { this.transactTime = transactTime; }
    
    public String toString() {
    	return String.format("OrderEntryEvent:[parentId: %d}|senderId: %s|side: %s|qty: %d|limit: %f|transactTime: %d]", this.parentId, this.senderId, this.side, this.qty, this.limit, this.transactTime);
    }
}