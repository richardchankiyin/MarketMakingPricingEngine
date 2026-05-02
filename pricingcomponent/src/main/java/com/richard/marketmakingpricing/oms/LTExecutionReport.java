package com.richard.marketmakingpricing.oms;

/**
 * Leg 1: OMS -> Dynamic Taker Client
 */
public class LTExecutionReport extends ExecutionReport {

    /**
     * @param targetClientId The ID of the Taker (captured from LTOrder.getSenderCompID())
     * @param clOrdID        The ID from the original LTOrder
     * @param status         ExecutionReportStatus Enum
     * @param px             Execution Price
     * @param qty            Execution Quantity
     * @param time           TransactTime
     * @param rejectReason   Reject Reason
     * @param text           text
     */
    public LTExecutionReport(String targetClientId, long clOrdID, ExecutionReportStatus status, 
                              double px, int qty, long time, String text) {
        super(
            System.nanoTime(), 
            clOrdID, 
            "OMS",           // Sender is always us (OMS)
            targetClientId,  // Target is now dynamic
            status, 
            px, 
            qty, 
            time, 
            null,
            text
        );
    }
	
	
	
    /**
     * @param targetClientId The ID of the Taker (captured from LTOrder.getSenderCompID())
     * @param clOrdID        The ID from the original LTOrder
     * @param status         ExecutionReportStatus Enum
     * @param px             Execution Price
     * @param qty            Execution Quantity
     * @param time           TransactTime
     * @param rejectReason   Reject Reason
     * @param text           text
     */
    public LTExecutionReport(String targetClientId, long clOrdID, ExecutionReportStatus status, 
                              double px, int qty, long time, int rejectReason, String text) {
        super(
            System.nanoTime(), 
            clOrdID, 
            "OMS",           // Sender is always us (OMS)
            targetClientId,  // Target is now dynamic
            status, 
            px, 
            qty, 
            time, 
            rejectReason,
            text
        );
    }
}