package com.richard.marketmakingpricing.oms;

public class HedgeOrder extends Order {
    private HedgeExecutionReport executionReport;

    public HedgeOrder(String lpId, boolean isSideBuy, int qty, double price, long time) {
        super(System.nanoTime(), "OMS", lpId, isSideBuy, qty, price, time);
    }

    public void setExecutionReport(HedgeExecutionReport er) { this.executionReport = er; }
    public HedgeExecutionReport getExecutionReport() { return executionReport; }
    
    public String toString() {
    	StringBuilder strBuilder = new StringBuilder(super.toString());
    	strBuilder.append("HedgeExecutionReport:[").append(executionReport).append("]");
    	return strBuilder.toString();
    }
}