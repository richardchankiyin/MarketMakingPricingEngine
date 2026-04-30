package com.richard.marketmakingpricing.oms;

/**
 * Leg 1: OMS -> LT_CLIENT
 */
public class LTExecutionReport extends ExecutionReport {

    /**
     * @param clOrdID The ID from the original LTOrder
     * @param status  Tag 39 (2=Filled, 8=Rejected)
     * @param px      Tag 31 (Execution Price)
     * @param qty     Tag 32 (Execution Quantity)
     * @param time    Tag 60 (TransactTime injected for deterministic testing)
     * @param text    Tag 58 (Reason for rejection, if any)
     */
    public LTExecutionReport(long clOrdID, String status, double px, int qty, long time, String text) {
        super(
            System.nanoTime(), // execID
            clOrdID, 
            "OMS",             // Sender is the Venue
            "LT_CLIENT",       // Target is the Taker
            status, 
            px, 
            qty, 
            time, 
            text
        );
    }
}