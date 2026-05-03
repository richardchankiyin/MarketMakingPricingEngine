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
    private final ScheduledExecutorService scheduler;
    private final ExecutorService lpPool;
    private final ExecutorService ltPool;
    private final Random random = new Random();
    private double refPrice;
    private final double volatility;
    private final int numberOfLPs;
    private final int numberOfLTs;
    private final int lpBidAskSizeFloor;
    private final int lpLatencyMillisec;
    private final int ltIntervalMillisec;
    private final int pricingEngineQuoteSize;
    
    public MarketGenerator() {
    	this(100, 0.05, 12, 20, 200, 1, 3, 0.0005, 0.001, 0.06, 500);
    }
    
    
    public MarketGenerator(double initialRefPrice, double volatility
    		, int numberOfLPs, int numberOfLTs, int lpBidAskSizeFloor, int lpLatencyMillisec, int ltIntervalMillisec
    		, double pricingEngineTickSize, double pricingEngineMinProfitMargin
    		, double pricingEngineMaxSignalSkew, int pricingEngineQuoteSize) {
        this.refPrice = initialRefPrice;
        this.volatility = volatility;
        this.numberOfLPs = numberOfLPs;
        this.numberOfLTs = numberOfLTs;
        this.lpBidAskSizeFloor = lpBidAskSizeFloor;
        this.lpLatencyMillisec = lpLatencyMillisec;
        this.ltIntervalMillisec = ltIntervalMillisec;
        this.pricingEngineQuoteSize = pricingEngineQuoteSize;
        // Thread Pools
        this.lpPool = Executors.newFixedThreadPool(this.numberOfLPs);
        this.ltPool = Executors.newFixedThreadPool(this.numberOfLTs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // 1. Initialize Ecosystem Components
        this.aggregator = new PriceAggregator(this.numberOfLPs);
        this.signalEmitter = new SignalEmitter();
        this.tcaManager = new TCAManager();
        this.omsHandler = new OMSHandler();
        this.engine = new PricingEngine(this.aggregator, this.signalEmitter
        		, pricingEngineTickSize, pricingEngineMinProfitMargin, pricingEngineMaxSignalSkew, pricingEngineQuoteSize);

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

        // 3. Wiring of different components to setup event driven architecture
        this.aggregator.addMarketListener(this.signalEmitter);
        this.aggregator.addMarketListener(this.engine);
        this.aggregator.addBookListener(omsHandler); // For book-level hedging
        
        this.engine.addListener(this.omsHandler); // For internal quote validation
        
        this.omsHandler.addOrderReplyListener(this.tcaManager); // For analytics
        
        log.info("MarketGenerator initialized: initialRefPrice: {} volatility: {}, numberOfLPs: {}, numberOfLTs: {} "
        		+ "lpBidAskSizeFloor: {} lpLatencyMillisec: {} ltIntervalMillisec: {} pricingEngineTickSize: {} pricingEngineMinProfitMargin: {} "
        		+ "pricingEngineMaxSignalSkew: {} pricingEngineQuoteSize: {}"
        		, initialRefPrice, volatility
        		, numberOfLPs, numberOfLTs, lpBidAskSizeFloor
        		, lpLatencyMillisec, ltIntervalMillisec
        		, pricingEngineTickSize, pricingEngineMinProfitMargin
        		, pricingEngineMaxSignalSkew, pricingEngineQuoteSize);
    }

    public void startSimulation() {
    	log.info("Booting Market Making Ecosystem...");

        // 1. Start the recurring Market Tick immediately.
        // LPs start quoting and the book begins to fill.
        scheduler.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
        
        log.info("Price discovery phase initiated. Waiting for order book to stabilize...");

        // 2. Schedule Takers to start after a 5-second warm-up delay
        /*
        scheduler.schedule(() -> {
            log.info("Warm-up complete. Launching Liquidity Taker activities.");
            startLTs();
        }, 10, TimeUnit.SECONDS);
        */
    }

    
    private int generateLBidAskSize() {
    	return lpBidAskSizeFloor + random.nextInt(lpBidAskSizeFloor);
    }
    
    private void tick() {
        try {
            refPrice += (random.nextDouble() - 0.5) * volatility;

            // Update LPs
            for (int i = 1; i <= numberOfLPs; i++) {
                final String lpId = "LP_" + i;
                lpPool.submit(() -> {
                	
                	// individual LP runs as dedicated thread/runnable here
                	
                	try {
	                	// different LP is going to generate their quote
	                    double lpSpread = 0.02 + (random.nextDouble() * 0.04);
	                    double lpBid = refPrice - (lpSpread / 2.0);
	                    double lpAsk = refPrice + (lpSpread / 2.0);
	                    int lpSize = generateLBidAskSize();
	
	                    for (LPQuoteListener l : lpListeners) {
	                        l.onLPUpdate(lpId, refPrice, lpBid, lpSize, lpAsk, lpSize);
	                    }
	                    aggregator.onUpdate(lpId, lpBid, lpSize, lpAsk, lpSize);
	                    Thread.sleep(lpLatencyMillisec);
	                }                		
                	catch (InterruptedException ie) {
                		log.warn("Thread interrupted unexpectedly", ie);
                	}
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
    
    
    private void startLTs() {
        for (int i = 1; i <= numberOfLTs; i++) {
            final String takerId = "LT_" + i;
            ltPool.submit(() -> {
                log.info("LT: {} is now active", takerId);
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // Random delay between orders to simulate human/algo "thinking" time
                        // e.g., if takerIntervalMs is 500, sleep between 500ms and 1000ms
                        long sleepTime = ltIntervalMillisec + random.nextInt(ltIntervalMillisec);
                        Thread.sleep(sleepTime);

                        publishTakerOrder(takerId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Error in Taker thread {}", takerId, e);
                    }
                }
            });
        }
    }
    
    private void publishTakerOrder(String takerId) {
        long sequence = ringBuffer.next();
        try {
            OrderEntryEvent event = ringBuffer.get(sequence);
            event.reset();
            event.setSenderId(takerId);
            event.setSide(random.nextBoolean() ? "BUY" : "SELL");
            
            // assuming LT monitors our order book offering. Therefore they will 
            // never send size larger than we quote
            int quantity = 10 + random.nextInt(pricingEngineQuoteSize - 10);            
            event.setQty(quantity);
            
            // Use a slight offset from refPrice to simulate aggressive/passive limit orders
            double limitPrice = event.getSide().equals("BUY") ? refPrice * 1.00005 : refPrice * (1 - 0.00005);
            event.setLimit(limitPrice);
            
            long transactTime = System.nanoTime();
            event.setTransactTime(transactTime);
            event.setParentId(transactTime);
        } finally {
            ringBuffer.publish(sequence);
        }
    }
    
    
    @Deprecated
    private void simulateTakerOrder() {
        long sequence = ringBuffer.next();
        try {
            OrderEntryEvent event = ringBuffer.get(sequence);
            event.reset();
            event.setSenderId("LT_" + (1 + random.nextInt(this.numberOfLTs)));
            event.setSide(random.nextBoolean() ? "BUY" : "SELL");
            event.setQty(10 + random.nextInt(200));
            event.setLimit(event.getSide().equals("BUY") ? refPrice + 0.01 : refPrice - 0.01);
            long transactTime = System.nanoTime();
            event.setTransactTime(transactTime);
            event.setParentId(transactTime);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    public void stopSimulation() {
        scheduler.shutdown();
        lpPool.shutdown();
        ltPool.shutdown();
        disruptor.shutdown();
    }

    // Accessors for External Wiring (Gateway)
    public void addLPQuoteListener(LPQuoteListener l) { this.lpListeners.add(l); }
    public void addPricingListener(PricingListener l) { this.engine.addListener(l); }
    public void addSignalListener(SignalListener l) { this.signalEmitter.addListener(l); }
    public void addBookListener(OrderBookUpdateListener l) { this.aggregator.addBookListener(l); }
    public void addTcaListener(TCAUpdateListener l) { this.tcaManager.addListener(l); }
}