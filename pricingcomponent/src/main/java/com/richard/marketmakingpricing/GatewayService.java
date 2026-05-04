package com.richard.marketmakingpricing;

import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.richard.marketmakingpricing.tca.ClientMetrics;
import com.richard.marketmakingpricing.tca.FirmMetrics;

public class GatewayService {
    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    
	private final LPUpdate reusableLPUpdate = new LPUpdate();
	private final QuoteUpdate reusableQuoteUpdate = new QuoteUpdate();
	private final SignalUpdate reusableSignalUpdate = new SignalUpdate();
	private final FullBookUpdate reusableBookUpdate = new FullBookUpdate();
    private final TCAUpdate reusableTCAUpdate = new TCAUpdate();

    private final Javalin app;
    private final ConcurrentLinkedQueue<SseClient> lpclients = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SseClient> pricingengineclients = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SseClient> signalclients = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SseClient> bookclients = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SseClient> tcaclients = new ConcurrentLinkedQueue<>();
    
    private final long intervalNanos;
    private final AtomicLong lastPushLPUpdateTime = new AtomicLong(0);
    private final AtomicLong lastPushPriceUpdateTime = new AtomicLong(0);    
    private final AtomicLong lastPushSignalUpdateTime = new AtomicLong(0);
    private final AtomicLong lastPushBookUpdateTime = new AtomicLong(0);
    private final AtomicLong lastPushTCAUpdateTime = new AtomicLong(0);

    public GatewayService(int port, int intervalMs) {
    	this(port,intervalMs,true);
    }
    
    public GatewayService(int port, int intervalMs, boolean useVirtualThread) {
    	// Convert MS to Nanos for higher precision comparison
        this.intervalNanos = intervalMs * 1_000_000L;

        
    	app = Javalin.create(config -> {
    		config.concurrency.useVirtualThreads = useVirtualThread;
    		
    		config.routes.sse("/lpquote", client -> {
    		    client.keepAlive();
    		    client.onClose(() -> lpclients.remove(client));
    		    lpclients.add(client);
    		    client.sendEvent("info", "Welcome to Gateway LPQuote");    			
    		});
    		
    		config.routes.sse("/priceengine", client -> {
    		    client.keepAlive();
    		    client.onClose(() -> pricingengineclients.remove(client));
    		    pricingengineclients.add(client);
    		    client.sendEvent("info", "Welcome to Gateway PriceEngine");    			
    		});
    		
    		config.routes.sse("/signal", client -> {
    		    client.keepAlive();
    		    client.onClose(() -> signalclients.remove(client));
    		    signalclients.add(client);
    		    client.sendEvent("info", "Welcome to Gateway Signal");   
    		});
    		
    		config.routes.sse("/fullbook", client -> {
                client.keepAlive();
                client.onClose(() -> bookclients.remove(client));
                bookclients.add(client);
                client.sendEvent("info", "Welcome to Gateway Full Book");
            });
    		
    		config.routes.sse("/tca", client -> {
                client.keepAlive();
                client.onClose(() -> tcaclients.remove(client));
                tcaclients.add(client);
                client.sendEvent("info", "Welcome to Gateway TCA");
            });
    				
    	}).start(port);
        
    }
    
    /**
     * Pushes price updates to /priceengine
     */
    public void pushPriceUpdate(double bid, int bSize, double ask, int aSize, double mid) {
        if (pricingengineclients.isEmpty()) return;

        // check time window. If prev sent was too early we will discard
        long now = System.nanoTime();
        if (now - lastPushPriceUpdateTime.get() < intervalNanos) return;

        if (lastPushPriceUpdateTime.getAndSet(now) != now) { // Simple atomic gate
            synchronized (reusableQuoteUpdate) {
                reusableQuoteUpdate.update(bid, bSize, ask, aSize, mid);
                for (SseClient client : pricingengineclients) {
                    client.sendEvent("quote", reusableQuoteUpdate);
                }
            }
        }
    }

    /**
     * Pushes signal updates to /signal
     */
    public void pushSignalUpdate(double signal) {
        if (signalclients.isEmpty()) return;

        long now = System.nanoTime();
        if (now - lastPushSignalUpdateTime.get() < intervalNanos) return;

        if (lastPushSignalUpdateTime.getAndSet(now) != now) {
            synchronized (reusableSignalUpdate) {
                reusableSignalUpdate.update(signal);
                for (SseClient client : signalclients) {
                    client.sendEvent("signal", reusableSignalUpdate);
                }
            }
        }
    }
    
    
    
    /**
     * Pushes price updates to /lpquote
     */
    public void pushLPUpdate(String lpId, double ref, double bid, int bS, double ask, int aS) {
        if (lpclients.isEmpty()) return;

        long now = System.nanoTime();
        if (now - lastPushLPUpdateTime.get() < intervalNanos) return;

        if (lastPushLPUpdateTime.getAndSet(now) != now) {
            synchronized (reusableLPUpdate) {
                reusableLPUpdate.update(lpId, ref, bid, bS, ask, aS);
                for (SseClient client : lpclients) {
                    client.sendEvent("lp_quote", reusableLPUpdate);
                }
            }
        }
    }
    
    
    /**
     * Pushes the aggregated order book to /fullbook using token window throttling.
     */
    public void pushFullBookUpdate(NavigableMap<Double, Map<String, Integer>> bids, 
                                   NavigableMap<Double, Map<String, Integer>> asks) {
        if (bookclients.isEmpty()) return;

        long now = System.nanoTime();
        if (now - lastPushBookUpdateTime.get() < intervalNanos) return;

        if (lastPushBookUpdateTime.getAndSet(now) != now) {
            synchronized (reusableBookUpdate) {
                reusableBookUpdate.update(bids, asks);
                for (SseClient client : bookclients) {
                    client.sendEvent("full_book", reusableBookUpdate);
                }
            }
        }
    }
    
    /** 
     * Pushes tca update
     */
    public void pushTCAUpdate(ClientMetrics clientMetrics, FirmMetrics firmMetrics) {
    	log.debug("Firm Metrics: {}", firmMetrics);
        // no matter we flush or not, we have to perform TCAUpdate as it stores 
        // the aggregated data. If we do not update here we will be at 
        // risk of not showing some of the LT
        
        reusableTCAUpdate.updateMetrics(clientMetrics, firmMetrics);
    	if (tcaclients.isEmpty()) return;
    	
        long now = System.nanoTime();
        if (now - lastPushTCAUpdateTime.get() < intervalNanos) return;
        
        if (lastPushTCAUpdateTime.getAndSet(now) != now) {
            synchronized (reusableTCAUpdate) {
            	log.debug("sending event: {}", clientMetrics);
                for (SseClient client : tcaclients) {
                    client.sendEvent("tcaupdate", reusableTCAUpdate);
                }
            }
        }
    }
    

    public void stop() {
        app.stop();
    }

}

class LPUpdate {
    private String lpId;
    private double refPrice;
    private double bid;
    private int bidSize;
    private double ask;
    private int askSize;

    public void update(String id, double ref, double b, int bS, double a, int aS) {
        this.lpId = id; this.refPrice = ref; this.bid = b; 
        this.bidSize = bS; this.ask = a; this.askSize = aS;
    }
    // Getters for Jackson...

	public String getLpId() {
		return lpId;
	}

	public double getRefPrice() {
		return refPrice;
	}

	public double getBid() {
		return bid;
	}

	public int getBidSize() {
		return bidSize;
	}

	public double getAsk() {
		return ask;
	}

	public int getAskSize() {
		return askSize;
	}
}


class SignalUpdate {
	private double signal;
	public SignalUpdate() {}
	public void update(double signal) { this.signal = signal; }
	public double getSignal() { return this.signal; }
}

class QuoteUpdate {
    private double bid;
    private int bidSize;
    private double ask;
    private int askSize;
    private double mid;

    // Standard constructor or empty constructor
    public QuoteUpdate() {}

    public void update(double bid, int bidSize, double ask, int askSize, double mid) {
        this.bid = bid;
        this.bidSize = bidSize;
        this.ask = ask;
        this.askSize = askSize;
        this.mid = mid;
    }
    
    public double getBid() { return this.bid; }
    public int getBidSize() { return this.bidSize; }
    public double getAsk() { return this.ask; }
    public int getAskSize() { return this.askSize; }
    public double getMid() { return this.mid; }
}

/**
 * Data Transfer Object for Full Book snapshots.
 */
class FullBookUpdate {
    private NavigableMap<Double, Map<String, Integer>> bids;
    private NavigableMap<Double, Map<String, Integer>> asks;
    private long timestamp;

    public void update(NavigableMap<Double, Map<String, Integer>> bids, 
                       NavigableMap<Double, Map<String, Integer>> asks) {
        this.bids = bids;
        this.asks = asks;
        this.timestamp = System.currentTimeMillis();
    }

    public NavigableMap<Double, Map<String, Integer>> getBids() { return bids; }
    public NavigableMap<Double, Map<String, Integer>> getAsks() { return asks; }
    public long getTimestamp() { return timestamp; }
}


class TCAUpdate {
    // The master state preserved across pushes
    private final Map<String, ClientMetrics> allClientMetrics = new ConcurrentHashMap<>();
    private FirmMetrics firmMetrics;
    private long timestamp;

    /**
     * Updates the registry with a single client's new metrics.
     * Note: This assumes single-threaded updates from the Gateway or 
     * external synchronization.
     */
    public void updateMetrics(ClientMetrics update, FirmMetrics firmUpdate) {
        // Simple put: replaces the old metrics object with the new one for this ID
        this.allClientMetrics.put(update.getClientId(), update);
        this.firmMetrics = firmUpdate;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters for serialization
    public Map<String, ClientMetrics> getAllClientMetrics() { return allClientMetrics; }
    public FirmMetrics getFirmMetrics() { return firmMetrics; }
    public long getTimestamp() { return timestamp; }
}

