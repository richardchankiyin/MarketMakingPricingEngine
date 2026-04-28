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
        GatewayService gateway = new GatewayService(7070, 500);

        // 3. Initialize the Engine
        // Params: Aggregator, SignalSource, Tick, Margin, Skew, Size
        PricingEngine engine = new PricingEngine(aggregator, signalEmitter, 0.01, 0.02, 0.15, 500);

        // 4. Initialize the Market Generator
        MarketGenerator generator = new MarketGenerator(aggregator, signalEmitter, engine);

        // 5. Wire Signal/Engine Output to the Gateway
        signalEmitter.addListener((signal)->{
        	gateway.pushSignalUpdate(signal);
        });        
        engine.addListener((bid, bSize, ask, aSize, iBid, iAsk) -> {
        	gateway.pushPriceUpdate(bid, bSize, ask, aSize, (iBid + iAsk) / 2.0);
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

}