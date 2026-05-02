package com.richard.marketmakingpricing.oms;

import com.lmax.disruptor.EventHandler;
import com.richard.marketmakingpricing.OrderBookUpdateListener;
import com.richard.marketmakingpricing.PricingListener;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core OMS Logic Handler.
 * Aligned to the existing TDD Test Suite.
 */
public class OMSHandler implements EventHandler<OrderEntryEvent>, PricingListener, OrderBookUpdateListener {
	private static final Logger log = LoggerFactory.getLogger(OMSHandler.class);
	private static final double INVALID_PRICE = -1;
    private final List<OrderUpdateListener> replyListeners = new CopyOnWriteArrayList<>();
    
    // Internal Pricing State (Gate 1: PriceEngine Validation)
    private volatile double internalBid, internalAsk;
    private volatile int internalBidSize, internalAskSize;

    // Market Depth State (Gate 2: Hedge Liquidity)
    private final AtomicReference<NavigableMap<Double, Map<String, Integer>>> bidsReference = 
        new AtomicReference<>(new TreeMap<>(Collections.reverseOrder()));
    private final AtomicReference<NavigableMap<Double, Map<String, Integer>>> asksReference = 
        new AtomicReference<>(new TreeMap<>());
    
    public void addOrderReplyListener(OrderUpdateListener listener) {
        this.replyListeners.add(listener);
    }

    @Override
    public void onQuoteUpdate(double bid, int bSize, double ask, int aSize, double iBid, double iAsk) {
        this.internalBid = bid;
        this.internalBidSize = bSize;
        this.internalAsk = ask;
        this.internalAskSize = aSize;
    }

    @Override
    public void onFullBookUpdate(NavigableMap<Double, Map<String, Integer>> bids, 
                                NavigableMap<Double, Map<String, Integer>> asks) {
        // Deep copy to ensure thread safety
        this.bidsReference.set(new TreeMap<>(bids));
        this.asksReference.set(new TreeMap<>(asks));
    }

    @Override
    public void onEvent(OrderEntryEvent event, long sequence, boolean endOfBatch) {
        final long now = System.currentTimeMillis();
        final boolean isBuy = event.getSide().equalsIgnoreCase("BUY");
        
        LTOrder ltOrder = new LTOrder(event.getParentId(), event.getSenderId(), isBuy, event.getQty(), event.getLimit(), now);
        
        log.debug("OrderEntryEvent: {}", event);

        // GATE 1: Check against Internal Quote (PriceEngine Validation)
        double ltexecutionprice = getExecutionPrice(ltOrder);
        if (ltexecutionprice == INVALID_PRICE) {
            // MATCHES TEST: assertTrue(result.getExecutionReport().getText().contains("PriceEngine Validation"))
            rejectOrder(ltOrder, "Failed PriceEngine Validation (Price/Size)", now);
            return;
        }

        // GATE 2: Hedge Calculation (Hedge Liquidity)
        List<HedgeOrder> slices = calculateHedgesLockFree(isBuy, ltOrder.getOrderQty(), now);

        if (slices == null) {
            // MATCHES TEST: assertEquals("Insufficient Hedge Liquidity", result.getExecutionReport().getText())
            rejectOrder(ltOrder, "Insufficient Hedge Liquidity", now);
        } else {
            processFill(ltOrder, ltexecutionprice, slices, now);
        }
    }

    private double getExecutionPrice(LTOrder order) {
        if (order.isSideBuy()) {
        	double price = internalAsk;
        	int size = internalAskSize;
            if (order.getPrice() >= price && order.getOrderQty() <= size) {
                return price; // The price the client is filled at
            }
        } else {
        	double price = internalBid;
        	int size = internalBidSize;
            if (order.getPrice() <= internalBid && order.getOrderQty() <= size) {
                return price; // The price the client is filled at
            }
        }
        return INVALID_PRICE; // Signal for reject
    }
    
    
    private List<HedgeOrder> calculateHedgesLockFree(boolean isBuy, int qty, long now) {
        NavigableMap<Double, Map<String, Integer>> book = isBuy ? asksReference.get() : bidsReference.get();
        if (book == null) return null;

        List<HedgeOrder> slices = new ArrayList<>();
        int remaining = qty;

        log.debug("isBuy: {} qty: {}, time: {}, book: {}", isBuy, qty, now, book);
        
        for (Map.Entry<Double, Map<String, Integer>> level : book.entrySet()) {
            double price = level.getKey();
            for (Map.Entry<String, Integer> lpEntry : level.getValue().entrySet()) {
                int take = Math.min(remaining, lpEntry.getValue());
                
                HedgeOrder ho = new HedgeOrder(lpEntry.getKey(), isBuy, take, price, now);
                // Ensure HedgeExecutionReport has getLastQty()
                ho.setExecutionReport(new HedgeExecutionReport(lpEntry.getKey(), ho.getClOrdID(), ExecutionReportStatus.FILLED, price, take, now));
                
                slices.add(ho);
                remaining -= take;
                if (remaining == 0) return slices;
            }
        }
        return null; // FOK failure
    }

    private void processFill(LTOrder order, double ltexecprice, List<HedgeOrder> slices, long now) {
        //double totalNotional = 0;
        //for (HedgeOrder h : slices) {
        //    totalNotional += (h.getPrice() * h.getOrderQty());
        //}
        //double avgPrice = totalNotional / order.getOrderQty();

        // Ensure LTExecutionReport has getOrdStatus() and getLastQty()
        order.setExecutionReport(new LTExecutionReport(order.getSenderCompID(), order.getClOrdID(), 
            ExecutionReportStatus.FILLED, ltexecprice, order.getOrderQty(), now, "Filled"));
        
        slices.forEach(order::addHedgeOrder);
        broadcast(order);
    }

    private void rejectOrder(LTOrder order, String reason, long now) {
        order.setExecutionReport(new LTExecutionReport(order.getSenderCompID(), order.getClOrdID(), 
            ExecutionReportStatus.REJECTED, 0, 0, now, reason));
        broadcast(order);
    }

    private void broadcast(LTOrder order) {
        for (OrderUpdateListener l : replyListeners) {
            l.onOMSReply(order);
        }
    }
}