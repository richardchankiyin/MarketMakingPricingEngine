package com.richard.marketmakingpricing;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.richard.marketmakingpricing.oms.*;
import com.richard.marketmakingpricing.tca.TCAManager;
import com.richard.marketmakingpricing.tca.TCAUpdateListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

public class MarketGenerator {
    private static final Logger log = LoggerFactory.getLogger(MarketGenerator.class);
    private final List<LPQuoteListener> lpListeners = new CopyOnWriteArrayList<>();
    
    // Internal Components
    private final PriceAggregator aggregator;
    private final SignalEmitter signalEmitter;
    private final PricingEngine engine;
    private final OMSHandler omsHandler;
    private final TCAManager tcaManager;
    
    // Disruptor Infrastructure
    private final Disruptor<OrderEntryEvent> disruptor;
    private final RingBuffer<OrderEntryEvent> ringBuffer;

    // Simulation Threads & State
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService lpPool;
    private final Random random = new Random();
    private double refPrice;
    private final double volatility;
    private final int numberOfLPs;
    private final int numberOfTakers;

    public MarketGenerator(double initialRefPrice, double volatility, int numberOfLPs, int numberOfTakers) {
        this.refPrice = initialRefPrice;
        this.volatility = volatility;
        this.numberOfLPs = numberOfLPs;
        this.numberOfTakers = numberOfTakers;
        this.lpPool = Executors.newFixedThreadPool(numberOfLPs);

        // 1. Initialize Ecosystem Components
        this.aggregator = new PriceAggregator(numberOfLPs);
        this.signalEmitter = new SignalEmitter();
        this.tcaManager = new TCAManager();
        this.omsHandler = new OMSHandler();
        this.engine = new PricingEngine(aggregator, signalEmitter, 0.0005, 0.001, 0.06, 500);

        // 2. Setup Disruptor
        this.disruptor = new Disruptor<>(
                OrderEntryEvent::new, 
                1024, 
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE, 
                new YieldingWaitStrategy()
        );
        this.disruptor.handleEventsWith(omsHandler);
        this.ringBuffer = this.disruptor.start();

        // 3. Internal Wiring (Ecosystem Logic)
        this.aggregator.addMarketListener(this.signalEmitter);
        this.aggregator.addMarketListener(this.engine);
        this.aggregator.addBookListener(omsHandler); // For book-level hedging
        
        this.engine.addListener(this.omsHandler); // For internal quote validation
        
        this.omsHandler.addOrderReplyListener(this.tcaManager); // For analytics
        
        log.info("MarketGenerator Ecosystem initialized: Mid={}, LPs={}, Takers={}", 
                 refPrice, numberOfLPs, numberOfTakers);
    }

    public void startSimulation() {
        log.info("Starting Simulation...");
        scheduler.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            refPrice += (random.nextDouble() - 0.5) * volatility;

            // Update LPs
            for (int i = 1; i <= numberOfLPs; i++) {
                final String lpId = "LP_" + i;
                lpPool.submit(() -> {
                    double lpSpread = 0.02 + (random.nextDouble() * 0.04);
                    double lpBid = refPrice - (lpSpread / 2.0);
                    double lpAsk = refPrice + (lpSpread / 2.0);
                    int lpSize = 200 + random.nextInt(800);

                    for (LPQuoteListener l : lpListeners) {
                        l.onLPUpdate(lpId, refPrice, lpBid, lpSize, lpAsk, lpSize);
                    }
                    aggregator.onUpdate(lpId, lpBid, lpSize, lpAsk, lpSize);
                });
            }

            // Taker Logic
            if (random.nextDouble() > 0.7) {
                simulateTakerOrder();
            }
        } catch (Exception e) {
            log.error("Simulation error", e);
        }
    }

    private void simulateTakerOrder() {
        long sequence = ringBuffer.next();
        try {
            OrderEntryEvent event = ringBuffer.get(sequence);
            event.reset();
            event.setSenderId("LT_" + (1 + random.nextInt(numberOfTakers)));
            event.setSide(random.nextBoolean() ? "BUY" : "SELL");
            event.setQty(10 + random.nextInt(200));
            event.setLimit(event.getSide().equals("BUY") ? refPrice + 0.01 : refPrice - 0.01);
            event.setParentId(System.nanoTime());
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    public void stopSimulation() {
        scheduler.shutdown();
        lpPool.shutdown();
        disruptor.shutdown();
    }

    // Accessors for External Wiring (Gateway)
    public void addLPQuoteListener(LPQuoteListener l) { this.lpListeners.add(l); }
    public void addPricingListener(PricingListener l) { this.engine.addListener(l); }
    public void addSignalListener(SignalListener l) { this.signalEmitter.addListener(l); }
    public void addBookListener(OrderBookUpdateListener l) { this.aggregator.addBookListener(l); }
    public void addTcaListener(TCAUpdateListener l) { this.tcaManager.addListener(l); }
}