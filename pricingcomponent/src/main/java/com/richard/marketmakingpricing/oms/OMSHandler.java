package com.richard.marketmakingpricing.oms;

import com.lmax.disruptor.EventHandler;
import com.richard.marketmakingpricing.MarketUpdateListener;
import com.richard.marketmakingpricing.OrderBookUpdateListener;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core OMS Logic Handler.
 * Orchestrates validation against PriceEngine and hedging against PriceAggregator.
 */
public class OMSHandler implements EventHandler<OrderEntryEvent>, MarketUpdateListener, OrderBookUpdateListener {

    private final OrderUpdateListener replyChannel;
    
    // Internal Quote Cache (L1) - volatile for visibility across Disruptor/Market threads
    private volatile double internalBid, internalAsk;
    private volatile int internalBidSize, internalAskSize;

    // Lock-Free Book Snapshots for Hedging (L2)
    private final AtomicReference<NavigableMap<Double, Map<String, Integer>>> bidsReference = 
        new AtomicReference<>(new TreeMap<>(Collections.reverseOrder()));
    private final AtomicReference<NavigableMap<Double, Map<String, Integer>>> asksReference = 
        new AtomicReference<>(new TreeMap<>());

    public OMSHandler(OrderUpdateListener replyChannel) {
        this.replyChannel = replyChannel;
    }

    /**
     * Entry point from LMAX Disruptor for incoming LT Orders.
     */
    @Override
    public void onEvent(OrderEntryEvent event, long sequence, boolean endOfBatch) {
        final long now = System.currentTimeMillis();
        
        // 1. Capture dynamic Taker ID and normalize side
        final String takerId = (event.getSenderId() != null) ? event.getSenderId() : "UNKNOWN_TAKER";
        final boolean isBuy = event.getSide().equalsIgnoreCase("BUY") || event.getSide().equals("1");
        
        // 2. Instantiate LTOrder container
        LTOrder ltOrder = new LTOrder(event.getParentId(), takerId, isBuy, event.getQty(), event.getLimit(), now);

        // 3. Validation against Internal PriceEngine (L1)
        if (!isMarketable(ltOrder)) {
            ltOrder.setExecutionReport(new LTExecutionReport(
                ltOrder.getSenderCompID(), 
                ltOrder.getClOrdID(), 
                ExecutionReportStatus.REJECTED, 
                0, 0, now, "Failed PriceEngine Validation"
            ));
            replyChannel.onOMSReply(ltOrder);
            return;
        }

        // 4. Hedge Calculation: Traverse Lock-Free Book Snapshot
        List<HedgeOrder> slices = calculateHedgesLockFree(ltOrder.isSideBuy(), ltOrder.getOrderQty(), now);

        if (slices == null) {
            // FOK Reject: Not enough liquidity to cover the full size
            ltOrder.setExecutionReport(new LTExecutionReport(
                ltOrder.getSenderCompID(), 
                ltOrder.getClOrdID(), 
                ExecutionReportStatus.REJECTED, 
                0, 0, now, "Insufficient Hedge Liquidity"
            ));
        } else {
            // 5. Success Path: Fill Taker and Link Children
            double fillPrice = ltOrder.isSideBuy() ? internalAsk : internalBid;
            ltOrder.setExecutionReport(new LTExecutionReport(
                ltOrder.getSenderCompID(), 
                ltOrder.getClOrdID(), 
                ExecutionReportStatus.FILLED, 
                fillPrice, ltOrder.getOrderQty(), now, "Filled"
            ));
            
            for (HedgeOrder ho : slices) {
                ltOrder.addHedgeOrder(ho);
            }
        }

        // 6. Final Reply (Traced by TCA and eventual ClientApp)
        replyChannel.onOMSReply(ltOrder);
    }

    /**
     * Logic for Marketability: checks if LT limit price is aggressive enough vs internal quote.
     */
    private boolean isMarketable(LTOrder order) {
        if (order.isSideBuy()) {
            return internalAsk <= order.getPrice() && internalAskSize >= order.getOrderQty();
        } else {
            return internalBid >= order.getPrice() && internalBidSize >= order.getOrderQty();
        }
    }

    /**
     * Lock-free traversal of the market book. 
     * Captures a local reference to ensure consistency during the loop.
     */
    private List<HedgeOrder> calculateHedgesLockFree(boolean isSideBuy, int qtyToHedge, long now) {
        // Snapshot the current book
        NavigableMap<Double, Map<String, Integer>> currentBook = isSideBuy ? asksReference.get() : bidsReference.get();
        
        List<HedgeOrder> slices = new ArrayList<>();
        int remaining = qtyToHedge;

        for (Map.Entry<Double, Map<String, Integer>> level : currentBook.entrySet()) {
            double price = level.getKey();
            for (Map.Entry<String, Integer> lpEntry : level.getValue().entrySet()) {
                String lpId = lpEntry.getKey();
                int available = lpEntry.getValue();

                int take = Math.min(remaining, available);
                
                // Create HedgeOrder and its Fill Report
                HedgeOrder ho = new HedgeOrder(lpId, isSideBuy, take, price, now);
                ho.setExecutionReport(new HedgeExecutionReport(
                    lpId, ho.getClOrdID(), ExecutionReportStatus.FILLED, price, take, now
                ));
                
                slices.add(ho);
                remaining -= take;
                
                if (remaining == 0) return slices;
            }
        }
        return null; // Liquidity check failed for FOK
    }

    /**
     * Listener: PriceEngine Summary Updates
     */
    @Override
    public void onSummaryUpdate(double bB, int bS, double bA, int aS, double vB, double vA) {
        this.internalBid = bB;
        this.internalBidSize = bS;
        this.internalAsk = bA;
        this.internalAskSize = aS;
    }

    /**
     * Listener: PriceAggregator Full Book Updates.
     * Performs a lock-free swap of the book reference.
     */
    @Override
    public void onFullBookUpdate(NavigableMap<Double, Map<String, Integer>> bids, 
                                NavigableMap<Double, Map<String, Integer>> asks) {
        // Shallow copies to create a point-in-time immutable-like view for the OMS
        bidsReference.set(new TreeMap<>(bids));
        asksReference.set(new TreeMap<>(asks));
    }
}