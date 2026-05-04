package com.richard.marketmakingpricing.oms;

public abstract class ExecutionReport {
    private final long execID;
    private final long clOrdID;
    private final String senderCompID;
    private final String targetCompID;
    private final ExecutionReportStatus ordStatus;
    private final double lastPx;
    private final int lastQty;
    private final long transactTime;
    private final Integer rejectReason;
    private final String text;

    protected ExecutionReport(long execID, long clOrdID, String sender, String target, 
    		ExecutionReportStatus status, double px, int qty, long time, Integer rejectReason, String text) {
        this.execID = execID;
        this.clOrdID = clOrdID;
        this.senderCompID = sender;
        this.targetCompID = target;
        this.ordStatus = status;
        this.lastPx = px;
        this.lastQty = qty;
        this.transactTime = time;
        this.rejectReason = rejectReason;
        this.text = text;
    }

    public long getExecID() { return execID; }
    public long getClOrdID() { return clOrdID; }
    public String getSenderCompID() { return senderCompID; }
    public String getTargetCompID() { return targetCompID; }
    public String getOrdStatusFixTag() { return ordStatus.getFixTag39Value(); }
    public ExecutionReportStatus getOrdStatus() { return ordStatus; }
    public double getLastPx() { return lastPx; }
    public int getLastQty() { return lastQty; }
    public long getTransactTime() { return transactTime; }
    public Integer getRejectReason() { return rejectReason; }
    public String getText() { return text; }
    
    public String toString() {
    	return String.format("ExecutionReport[execID:%d|clOrdID:%d|senderCompID:%s|targetCompID:%s|ordStatus:%s|lastPx:%f|lastQty:%d|transactTime:%d|rejectReason:%d]"
    			, this.execID, this.clOrdID, this.senderCompID, this.targetCompID, this.ordStatus, this.lastPx, this.lastQty, this.transactTime, this.rejectReason);
    }
}