package com.richard.marketmakingpricing.tca;

public class FirmMetrics {
    private final int totalOrders;
    private final int totalFills;
    private final double totalPnL;
    private final double fillRate;

    public FirmMetrics(int totalOrders, int totalFills, double totalPnL) {
        this.totalOrders = totalOrders;
        this.totalFills = totalFills;
        this.totalPnL = totalPnL;
        this.fillRate = totalOrders == 0 ? 0 : (double) totalFills / totalOrders;
    }

    // Getters for JSON serialization/Streamlit
    public int getTotalOrders() { return totalOrders; }
    public int getTotalFills() { return totalFills; }
    public double getTotalPnL() { return totalPnL; }
    public double getFillRate() { return fillRate; }
    public String toString() {
    	return String.format("FirmMetrics - totalOrder: %d  totalFills: %d, totalPnL: %f fillRate: %f", this.totalOrders, this.totalFills, this.totalPnL, this.fillRate);
    }
}