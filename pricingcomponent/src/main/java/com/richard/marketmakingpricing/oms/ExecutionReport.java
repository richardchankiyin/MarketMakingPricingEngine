package com.richard.marketmakingpricing.oms;

public abstract class ExecutionReport {
    private final long execID;
    private final long clOrdID;
    private final String senderCompID;
    private final String targetCompID;
    private final String ordStatus;
    private final double lastPx;
    private final int lastQty;
    private final long transactTime;
    private final String text;

    protected ExecutionReport(long execID, long clOrdID, String sender, String target, 
                               String status, double px, int qty, long time, String text) {
        this.execID = execID;
        this.clOrdID = clOrdID;
        this.senderCompID = sender;
        this.targetCompID = target;
        this.ordStatus = status;
        this.lastPx = px;
        this.lastQty = qty;
        this.transactTime = time;
        this.text = text;
    }

    public long getExecID() { return execID; }
    public long getClOrdID() { return clOrdID; }
    public String getSenderCompID() { return senderCompID; }
    public String getTargetCompID() { return targetCompID; }
    public String getOrdStatus() { return ordStatus; }
    public double getLastPx() { return lastPx; }
    public int getLastQty() { return lastQty; }
    public long getTransactTime() { return transactTime; }
    public String getText() { return text; }
}