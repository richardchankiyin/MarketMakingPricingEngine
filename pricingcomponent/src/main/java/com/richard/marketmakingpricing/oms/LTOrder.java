package com.richard.marketmakingpricing.oms;

import java.util.ArrayList;
import java.util.List;

public class LTOrder extends Order {
    private LTExecutionReport executionReport;
    private final List<HedgeOrder> hedgeOrders = new ArrayList<>();

    public LTOrder(long clOrdID, String side, int qty, double limit, long time) {
        super(clOrdID, "LT_CLIENT", "OMS", side, qty, limit, time);
    }

    public void setExecutionReport(LTExecutionReport er) { this.executionReport = er; }
    public LTExecutionReport getExecutionReport() { return executionReport; }
    
    public void addHedgeOrder(HedgeOrder ho) { this.hedgeOrders.add(ho); }
    public List<HedgeOrder> getHedgeOrders() { return hedgeOrders; }
}
