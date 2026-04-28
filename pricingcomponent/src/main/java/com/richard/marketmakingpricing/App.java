package com.richard.marketmakingpricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        // 1. Initialize Infrastructure
        PriceAggregator aggregator = new PriceAggregator();
        SignalEmitter signalEmitter = new SignalEmitter();
        
        // 2. Initialize the Gateway (Javalin Server)
        GatewayService gateway = new GatewayService(7070);

        // 3. Initialize the Engine
        // Params: Aggregator, SignalSource, Tick, Margin, Skew, Size
        PricingEngine engine = new PricingEngine(aggregator, signalEmitter, 0.01, 0.02, 0.15, 500);

        // 4. Initialize the Market Generator
        MarketGenerator generator = new MarketGenerator(aggregator, signalEmitter, engine);

        // 5. Wire Engine Output to the Gateway
        // We wrap the primitives into a simple Record or Map for JSON conversion
        engine.addListener((bid, bSize, ask, aSize, iBid, iAsk) -> {
        	//log.info(">>>>>>PricingEngine QUOTE: Bid {} | Ask {}", bid, ask);
            QuoteUpdate update = new QuoteUpdate(bid, bSize, ask, aSize, (iBid + iAsk) / 2.0);
            gateway.pushPriceUpdate(update);
        });

        // 6. Graceful Shutdown Hook for Linux
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("System shutting down...");
            generator.stopSimulation();
            gateway.stop();
        }));

        // 7. Kick off the simulation
        log.info("All systems GO. Port: 7070");
        generator.startSimulation();
    }

    // Simple DTO for JSON Marshalling
    public record QuoteUpdate(double bid, int bidSize, double ask, int askSize, double mid) {}
}