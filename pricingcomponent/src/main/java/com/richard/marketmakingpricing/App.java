package com.richard.marketmakingpricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
	private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        // 1. Initialize External Gateway
        GatewayService gateway = new GatewayService(7070, 500);

        // 2. Initialize MarketGenerator

        MarketGenerator generator = new MarketGenerator();

        // 3. Perform Gateway Wiring (Control Plane responsibilities)
        generator.addLPQuoteListener(gateway::pushLPUpdate);
        
        generator.addSignalListener(gateway::pushSignalUpdate);
        
        generator.addPricingListener((bid, bSize, ask, aSize, iBid, iAsk) -> {
            gateway.pushPriceUpdate(bid, bSize, ask, aSize, (iBid + iAsk) / 2.0);
        });
        
        generator.addBookListener(gateway::pushFullBookUpdate);
        
        generator.addTcaListener(gateway::pushTCAUpdate);

        // 4. Lifecycle Control
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("System shutting down...");
            generator.stopSimulation();
            gateway.stop();
        }));

        log.info("System Control Plane Active. Starting Simulation Ecosystem...");
        generator.startSimulation();
    }

}