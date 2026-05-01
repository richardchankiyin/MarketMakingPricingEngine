package com.richard.marketmakingpricing.oms;

import com.lmax.disruptor.EventHandler;
import com.richard.marketmakingpricing.MarketUpdateListener;
import com.richard.marketmakingpricing.OrderBookUpdateListener;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core OMS Logic Handler.
 * Orchestrates validation against PriceEngine and hedging against PriceAggregator.
 */
public class OMSHandler implements EventHandler<OrderEntryEvent>, MarketUpdateListener, OrderBookUpdateListener {

    // 1. Refactored to support multiple listeners
    private final List<OrderUpdateListener> replyListeners = new CopyOnWriteArrayList<>();
    
    // Internal Quote Cache (L1) - volatile for visibility across Disruptor/Market threads
    private volatile double internalBid, internalAsk;
    private volatile int internalBidSize, internalAskSize;

    // Lock-Free Book Snapshots for Hedging (L2)
    private final AtomicReference<NavigableMap<Double, Map<String, Integer>>> bidsReference = 
        new AtomicReference<>(new TreeMap<>(Collections.reverseOrder()));
    private final AtomicReference<NavigableMap<Double, Map<String, Integer>>> asksReference = 
        new AtomicReference<>(new TreeMap<>());

    // 2. Updated constructor for multi-listener pattern
    public OMSHandler() {
    }

    public void addOrderReplyListener(OrderUpdateListener listener) {
        this.replyListeners.add(listener);
    }

    /**
     * Entry point from LMAX Disruptor for incoming LT Orders.
     */
    @Override
    public void onEvent(OrderEntryEvent event, long sequence, boolean endOfBatch) {
        final long now = System.currentTimeMillis();
        
        final String takerId = (event.getSenderId() != null) ? event.getSenderId() : "UNKNOWN_TAKER";
        final boolean isBuy = event.getSide().equalsIgnoreCase("BUY") || event.getSide().equals("1");
        
        LTOrder ltOrder = new LTOrder(event.getParentId(), takerId, isBuy, event.getQty(), event.getLimit(), now);

        // 3. Validation against Internal PriceEngine (L1) - Now includes Size Check
        if (!isMarketable(ltOrder)) {
            ltOrder.setExecutionReport(new LTExecutionReport(
                ltOrder.getSenderCompID(), 
                ltOrder.getClOrdID(), 
                ExecutionReportStatus.REJECTED, 
                0, 0, now, "Failed PriceEngine Validation (Price/Size)"
            ));
            broadcastReply(ltOrder);
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
            // 5. Success Path: Fill Taker using weighted average price from hedges
            double totalHedgeCost = slices.stream().mapToDouble(h -> h.getPrice() * h.getOrderQty()).sum();
            double fillPrice = totalHedgeCost / ltOrder.getOrderQty();

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

        // 6. Final Multi-cast Reply
        broadcastReply(ltOrder);
    }

    /**
     * Logic for Marketability: checks if LT limit price is aggressive enough AND size fits.
     */
    private boolean isMarketable(LTOrder order) {
        if (order.isSideBuy()) {
            // BUY: Limit must be >= Ask AND Qty must be <= Internal Ask Size
            return order.getPrice() >= internalAsk && order.getOrderQty() <= internalAskSize;
        } else {
            // SELL: Limit must be <= Bid AND Qty must be <= Internal Bid Size
            return order.getPrice() <= internalBid && order.getOrderQty() <= internalBidSize;
        }
    }

    private List<HedgeOrder> calculateHedgesLockFree(boolean isSideBuy, int qtyToHedge, long now) {
        NavigableMap<Double, Map<String, Integer>> currentBook = isSideBuy ? asksReference.get() : bidsReference.get();
        
        List<HedgeOrder> slices = new ArrayList<>();
        int remaining = qtyToHedge;

        for (Map.Entry<Double, Map<String, Integer>> level : currentBook.entrySet()) {
            double price = level.getKey();
            for (Map.Entry<String, Integer> lpEntry : level.getValue().entrySet()) {
                int take = Math.min(remaining, lpEntry.getValue());
                
                HedgeOrder ho = new HedgeOrder(lpEntry.getKey(), isSideBuy, take, price, now);
                ho.setExecutionReport(new HedgeExecutionReport(
                    lpEntry.getKey(), ho.getClOrdID(), ExecutionReportStatus.FILLED, price, take, now
                ));
                
                slices.add(ho);
                remaining -= take;
                
                if (remaining == 0) return slices;
            }
        }
        return null; 
    }

    private void broadcastReply(LTOrder order) {
        for (OrderUpdateListener listener : replyListeners) {
            listener.onOMSReply(order);
        }
    }

    /**
     * Listener: PriceEngine Summary Updates (Restored to original signature)
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
     */
    @Override
    public void onFullBookUpdate(NavigableMap<Double, Map<String, Integer>> bids, 
                                NavigableMap<Double, Map<String, Integer>> asks) {
        bidsReference.set(new TreeMap<>(bids));
        asksReference.set(new TreeMap<>(asks));
    }
}