package com.richard.marketmakingpricing;

import java.util.Random;
import java.util.concurrent.*;

public class MarketGenerator {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService lpPool = Executors.newFixedThreadPool(12);
    private final Random random = new Random();

    private final PriceAggregator aggregator;
    private final SignalEmitter signalEmitter;
    private final PricingEngine engine;

    private double refPrice = 100.00;
    private final double vol = 0.02;

    public MarketGenerator() {
        this.aggregator = new PriceAggregator();
        this.signalEmitter = new SignalEmitter();
        
        // 1. Engine listens to Aggregator (Market Data) and SignalEmitter (Alpha)
        this.engine = new PricingEngine(aggregator, signalEmitter, 0.01, 0.02, 0.15, 500);
        
        // 2. SignalEmitter listens to Aggregator to generate signal from price
        this.aggregator.addListener(signalEmitter);
        this.aggregator.addListener(engine);
    }
}