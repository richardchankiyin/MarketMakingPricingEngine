package com.richard.marketmakingpricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
	private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
    	log.info("Starting Market Making Platform Backend App...");

        // 1. Resolve Gateway Environment Variables
        int port = getEnvAsInt("GATEWAY_PORT", 7070);
        int gatewayInterval = getEnvAsInt("GATEWAY_INTERVAL_MS", 500);

        // 2. Resolve MarketGenerator Environment Variables
        double initialRefPrice = getEnvAsDouble("REF_PRICE", 100.0);
        double volatility = getEnvAsDouble("VOLATILITY", 0.05);
        int numberOfLPs = getEnvAsInt("NO_LPS", 12);
        int numberOfLTs = getEnvAsInt("NO_LTS", 3);
        int lpSizeFloor = getEnvAsInt("LP_SIZE_FLOOR", 200);
        int lpLatency = getEnvAsInt("LP_LATENCY_MS", 1);
        int ltInterval = getEnvAsInt("LT_INTERVAL_MS", 50);
        double ltPremium = getEnvAsDouble("LT_PREMIUM_RATIO", 0.00075);
        double tickSize = getEnvAsDouble("TICK_SIZE", 0.0005);
        double minProfit = getEnvAsDouble("MIN_PROFIT_MARGIN", 0.001);
        double maxSkew = getEnvAsDouble("MAX_SIGNAL_SKEW", 0.1);
        int quoteSize = getEnvAsInt("QUOTE_SIZE", 500);
        int threads = getEnvAsInt("APP_THREADS", 25);

        
        // 3. Initialize External Gateway
        // This is where your Streamlit dashboard or external clients will connect
        GatewayService gateway = new GatewayService(port, gatewayInterval);

        // 4. Initialize MarketGenerator with all parameters
        MarketGenerator generator = new MarketGenerator(
            initialRefPrice, volatility, numberOfLPs, numberOfLTs, 
            lpSizeFloor, lpLatency, ltInterval, ltPremium, 
            tickSize, minProfit, maxSkew, quoteSize, threads
        );
        
        // 5. Perform Gateway Wiring (Control Plane responsibilities)
        generator.addLPQuoteListener(gateway::pushLPUpdate);
        
        generator.addSignalListener(gateway::pushSignalUpdate);
        
        generator.addPricingListener((bid, bSize, ask, aSize, iBid, iAsk) -> {
            gateway.pushPriceUpdate(bid, bSize, ask, aSize, (iBid + iAsk) / 2.0);
        });
        
        generator.addBookListener(gateway::pushFullBookUpdate);
        
        generator.addTcaListener(gateway::pushTCAUpdate);

        // 6. Lifecycle Control
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("System shutting down...");
            generator.stopSimulation();
            gateway.stop();
        }));

        log.info("System Control Plane Active. Starting Simulation Ecosystem...");
        generator.startSimulation();
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    private static int getEnvAsInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getEnv(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.error("Invalid integer for {}: using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    private static double getEnvAsDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(getEnv(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.error("Invalid double for {}: using default {}", key, defaultValue);
            return defaultValue;
        }
    }
}