package com.richard.marketmakingpricing;

import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayService {
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
            // Primitive-to-String conversion happens ONLY when we pass the gate
            String json = String.format(
                "{\"bid\": %.4f, \"bidSize\": %d, \"ask\": %.4f, \"askSize\": %d, \"mid\": %.4f}",
                bid, bSize, ask, aSize, mid
            );

            for (SseClient client : clients) {
                try {
                    client.sendEvent("quote", json);
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