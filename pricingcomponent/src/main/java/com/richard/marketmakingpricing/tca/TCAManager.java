package com.richard.marketmakingpricing.tca;

import com.richard.marketmakingpricing.oms.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TCAManager implements OrderUpdateListener {
	private static final Logger log = LoggerFactory.getLogger(OrderUpdateListener.class);
    private final Map<String, ClientMetrics> clientStats = new ConcurrentHashMap<>();
    private final List<TCAUpdateListener> listeners = new CopyOnWriteArrayList<>();

    // Global Firm-level counters
    private final AtomicInteger globalTotalOrders = new AtomicInteger();
    private final AtomicInteger globalTotalFills = new AtomicInteger();
    private final DoubleAdder globalTotalPnL = new DoubleAdder();

    public void addListener(TCAUpdateListener listener) { this.listeners.add(listener); }

    @Override
    public void onOMSReply(LTOrder order) {
        globalTotalOrders.incrementAndGet();
        String clientId = order.getSenderCompID();
        ClientMetrics cMetrics = clientStats.computeIfAbsent(clientId, ClientMetrics::new);

        LTExecutionReport takerReport = order.getExecutionReport();
        
        if (takerReport.getOrdStatus() == ExecutionReportStatus.REJECTED) {
            cMetrics.recordReject();
        } else {
            processFill(order, cMetrics);
        }

        // Snapshot of Firm Metrics
        FirmMetrics fMetrics = new FirmMetrics(
            globalTotalOrders.get(),
            globalTotalFills.get(),
            globalTotalPnL.sum()
        );

        // Broadcast BOTH to all listeners
        for (TCAUpdateListener listener : listeners) {
            listener.onTCAUpdate(cMetrics, fMetrics);
        }
    }

    private void processFill(LTOrder order, ClientMetrics metrics) {

    	
        globalTotalFills.incrementAndGet();
        
        LTExecutionReport takerReport = order.getExecutionReport();
        double takerPrice = takerReport.getLastPx();
        int totalQty = takerReport.getLastQty();
        
        double totalHedgeCost = 0;
        for (HedgeOrder ho : order.getHedgeOrders()) {
            totalHedgeCost += (ho.getExecutionReport().getLastPx() * ho.getExecutionReport().getLastQty());
        }
        
        double avgHedgePrice = totalHedgeCost / totalQty;
        double pnl = order.isSideBuy() ? (takerPrice - avgHedgePrice) : (avgHedgePrice - takerPrice);
        double bpsPnL = (pnl / takerPrice) * 10000;
        
    	log.debug("TCA_TRACE|ID:{}|Is Buy:{}|TakerPx:{}|HedgeAvgPx:{}|HedgeCount:{}|PnL:{}", 
                order.getClOrdID(), order.isSideBuy(), takerPrice, avgHedgePrice, order.getHedgeOrders().size(), pnl);

        metrics.recordFill(pnl, bpsPnL);
        metrics.evaluateToxicity(); 
        
        globalTotalPnL.add(pnl); // Update desk PnL
    }
}