package com.richard.marketmakingpricing.tca;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

public class ClientMetrics {
	private final Map<Integer, Integer> rejectReasonCounts = new ConcurrentHashMap<>();
    private static final int UNKNOWN_REASON = 999; // Default for null/unexpected
    private final String clientId;
    private final AtomicInteger fills = new AtomicInteger();
    private final AtomicInteger rejects = new AtomicInteger();
    private final DoubleAdder totalPnL = new DoubleAdder();
    private final DoubleAdder totalBps = new DoubleAdder();
    
    private volatile boolean isToxic = false;

    public ClientMetrics(String clientId) { this.clientId = clientId; }

    public void recordFill(double pnl, double bps) { 
        fills.incrementAndGet();
        totalPnL.add(pnl); 
        totalBps.add(bps);
    }

    public void recordReject(Integer reasonCode) { 
    	// 1. Map null or 0 to our Unknown/Safety bucket
        int code = (reasonCode == null || reasonCode == 0) ? UNKNOWN_REASON : reasonCode;
        
        // 2. Update the specific reason distribution
        rejectReasonCounts.merge(code, 1, Integer::sum);
        
        // 3. Increment the global reject counter for the client
        rejects.incrementAndGet();
    }

    public Map<Integer, Integer> getRejectReasonCounts() {
        return rejectReasonCounts;
    }
    
    /**
     * Logic to determine toxicity. 
     * Threshold: Avg PnL < -2.0 Bps after at least 10 fills.
     */
    public void evaluateToxicity() {
        double avgBps = getAverageBps();
        if (fills.get() >= 10 && avgBps < -2.0) {
            this.isToxic = true;
        }
    }

    // Getters for Streamlit/TCA reporting
    public String getClientId() { return clientId; }
    public boolean isToxic() { return isToxic; }
    public int getFillCount() { return fills.get(); }
    public int getRejectCount() { return rejects.get(); }
    public double getTotalPnL() { return totalPnL.sum(); }
    public double getAverageBps() { 
        return fills.get() == 0 ? 0 : totalBps.sum() / fills.get(); 
    }
}