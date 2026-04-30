package com.richard.marketmakingpricing.oms;

public class HedgeOrder extends Order {
    private HedgeExecutionReport executionReport;

    public HedgeOrder(String lpId, String side, int qty, double price, long time) {
        super(System.nanoTime(), "OMS", lpId, side, qty, price, time);
    }

    public void setExecutionReport(HedgeExecutionReport er) { this.executionReport = er; }
    public HedgeExecutionReport getExecutionReport() { return executionReport; }
}