package com.richard.marketmakingpricing;

import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayService {
	private final QuoteUpdate reusableUpdate = new QuoteUpdate();
    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private final Javalin app;
    private final ConcurrentLinkedQueue<SseClient> clients = new ConcurrentLinkedQueue<>();
    
    private final long intervalNanos;
    private final AtomicLong lastPushPriceUpdateTime = new AtomicLong(0);

    public GatewayService(int port, int intervalMs) {
    	// Convert MS to Nanos for higher precision comparison
        this.intervalNanos = intervalMs * 1_000_000L;
        
    	app = Javalin.create(config -> {
    		config.routes.sse("/stream", client -> {
    		    client.keepAlive();
    		    client.onClose(() -> clients.remove(client));
    		    clients.add(client);
    		    client.sendEvent("info", "Welcome to Gateway");    			
    		});
    	}).start(port);
        
    }

    
    /**
     * Throttled push using a Time-Window / Token-Bucket logic.
     * Drops updates if they arrive faster than the defined interval.
     */
    public void pushPriceUpdate(double bid, int bSize, double ask, int aSize, double mid) {
        if (clients.isEmpty()) return;

        long now = System.nanoTime();
        long last = lastPushPriceUpdateTime.get();

        // Check if enough time has passed since the last push
        if (now - last < intervalNanos) {
            return; // Discard: Throttled
        }

        // Try to update the timestamp. If another thread beat us to it, we skip.
        if (lastPushPriceUpdateTime.compareAndSet(last, now)) {
        	// 1. Update the mutable object's state
            reusableUpdate.update(bid, bSize, ask, aSize, mid);

            // 2. Pass the object directly. 
            // Javalin/Jackson will serialize the current state of this object.
            for (SseClient client : clients) {
                try {
                    client.sendEvent("quote", reusableUpdate);
                } catch (Exception e) {
                    clients.remove(client);
                }
            }
        }
    }
    /**
     * Broadcast OMS/TCA data (Orders, Fills, Parent/Child links)
     */
    public void pushOrderUpdate(Object data) {
        broadcast("order_event", data);
    }

    /**
     * Broadcast PnL & Performance metrics
     */
    public void pushTcaUpdate(Object data) {
        broadcast("pnl_update", data);
    }

    private void broadcast(String eventName, Object data) {
        for (SseClient client : clients) {
            client.sendEvent(eventName, data);
        }
    }

    public void stop() {
        app.stop();
    }

}

class QuoteUpdate {
    public double bid;
    public int bidSize;
    public double ask;
    public int askSize;
    public double mid;

    // Standard constructor or empty constructor
    public QuoteUpdate() {}

    public void update(double bid, int bidSize, double ask, int askSize, double mid) {
        this.bid = bid;
        this.bidSize = bidSize;
        this.ask = ask;
        this.askSize = askSize;
        this.mid = mid;
    }
}
