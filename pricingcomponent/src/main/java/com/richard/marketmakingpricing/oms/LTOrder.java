package com.richard.marketmakingpricing.oms;

import java.util.ArrayList;
import java.util.List;

public class LTOrder extends Order {
    public LTOrder(long clOrdID, String senderId, boolean isBuy, int qty, double limit, long time) {
        // senderId = Taker, targetId = OMS
        super(clOrdID, senderId, "OMS", isBuy, qty, limit, time);
    }
    
    private LTExecutionReport executionReport;
    private final List<HedgeOrder> hedgeOrders = new ArrayList<>();

    public void setExecutionReport(LTExecutionReport er) { this.executionReport = er; }
    public LTExecutionReport getExecutionReport() { return executionReport; }
    
    public void addHedgeOrder(HedgeOrder ho) { this.hedgeOrders.add(ho); }
    public List<HedgeOrder> getHedgeOrders() { return hedgeOrders; }
}
