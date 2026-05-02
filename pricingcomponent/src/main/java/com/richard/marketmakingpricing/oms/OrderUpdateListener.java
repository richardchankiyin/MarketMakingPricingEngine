package com.richard.marketmakingpricing.oms;
/**
 * Interface for components listening to the final outcome of an OMS execution.
 * Typically implemented by the FIX Gateway or the TCA Module.
 */
public interface OrderUpdateListener {
    
    /**
     * Invoked when the OMS has finished processing an LTOrder.
     * 
     * @param order The root order containing the LTExecutionReport 
     *              and the list of HedgeOrders.
     */
    void onOMSReply(LTOrder order);
}
