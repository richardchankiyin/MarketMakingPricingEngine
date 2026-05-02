package com.richard.marketmakingpricing.oms;

/**
 * Leg 2: LP -> OMS
 */
public class HedgeExecutionReport extends ExecutionReport {
    
    private final String lpId;

    /**
     * @param lpId    The ID of the LP providing the hedge
     * @param clOrdID The ID from the HedgeOrder sent by the OMS
     * @param status  Tag 39 (2=Filled, 8=Rejected)
     * @param px      Tag 31 (LP Execution Price)
     * @param qty     Tag 32 (LP Execution Quantity)
     * @param time    Tag 60 (TransactTime injected)
     */
    public HedgeExecutionReport(String lpId, long clOrdID, ExecutionReportStatus status, double px, int qty, long time) {
        super(
            System.nanoTime(), // execID
            clOrdID, 
            lpId,              // Sender is the LP
            "OMS",             // Target is the OMS
            status, 
            px, 
            qty, 
            time, 
            null,
            null               // Hedges usually succeed or fail silently in FOK
        );
        this.lpId = lpId;
    }

    public String getLpId() {
        return lpId;
    }
}