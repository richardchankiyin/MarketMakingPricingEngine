package com.richard.marketmakingpricing;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
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
    private final ScheduledExecutorService threadPool;
    private final Random random = new Random();
    // this needs to ensure visibility btw LP and LT threads
    private volatile double refPrice;
    private final double volatility;
    private final int numberOfLPs;
    private final int numberOfLTs;
    private final int lpBidAskSizeFloor;
    private final int lpLatencyMillisec;
    private final int ltIntervalMillisec;
    private final double ltpremiumratio;
    private final int pricingEngineQuoteSize;
    // this govern the size of threadpool, usually = numberOfLPs + numberOfLTs + Disruptor (1) + PriceAggregator (2) + Internal Simulation threads (2)
    private final int noOfThreads;
    
    public MarketGenerator() {
    	this(100, 0.05, 12, 3, 200, 1, 50, 0.00075, 0.0005, 0.001, 0.1, 500, 20);
    }
    
    
    public MarketGenerator(double initialRefPrice, double volatility
    		, int numberOfLPs, int numberOfLTs, int lpBidAskSizeFloor, int lpLatencyMillisec, int ltIntervalMillisec
    		, double ltpremiumratio, double pricingEngineTickSize, double pricingEngineMinProfitMargin
    		, double pricingEngineMaxSignalSkew, int pricingEngineQuoteSize, int nofThreads) {
        this.refPrice = initialRefPrice;
        this.volatility = volatility;
        this.numberOfLPs = numberOfLPs;
        this.numberOfLTs = numberOfLTs;
        this.lpBidAskSizeFloor = lpBidAskSizeFloor;
        this.lpLatencyMillisec = lpLatencyMillisec;
        this.ltIntervalMillisec = ltIntervalMillisec;
        this.ltpremiumratio = ltpremiumratio;
        this.pricingEngineQuoteSize = pricingEngineQuoteSize;
        // 1. Create a single, named ThreadFactory
        ThreadFactory engineFactory = new ThreadFactoryBuilder()
                .setNameFormat("market-generator-threadpool-%d")
                .setDaemon(true)
                .build();
        this.noOfThreads = nofThreads;
        // Thread Pools
        this.threadPool = Executors.newScheduledThreadPool(this.noOfThreads, engineFactory);
        
        // 1. Initialize Ecosystem Components
        this.aggregator = new PriceAggregator(this.numberOfLPs, threadPool);
        this.signalEmitter = new SignalEmitter();
        this.tcaManager = new TCAManager();
        this.omsHandler = new OMSHandler();
        this.engine = new PricingEngine(this.aggregator, this.signalEmitter
        		, pricingEngineTickSize, pricingEngineMinProfitMargin, pricingEngineMaxSignalSkew, pricingEngineQuoteSize);

        // 2. Setup Disruptor
        this.disruptor = new Disruptor<>(
                OrderEntryEvent::new, 
                1024, 
                engineFactory,
                ProducerType.MULTI, 
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
        		+ "lpBidAskSizeFloor: {} lpLatencyMillisec: {} ltIntervalMillisec: {} ltpremiumratio: {} pricingEngineTickSize: {} pricingEngineMinProfitMargin: {} "
        		+ "pricingEngineMaxSignalSkew: {} pricingEngineQuoteSize: {} noofThreads: {}"
        		, initialRefPrice, volatility
        		, numberOfLPs, numberOfLTs, lpBidAskSizeFloor
        		, lpLatencyMillisec, ltIntervalMillisec, ltpremiumratio
        		, pricingEngineTickSize, pricingEngineMinProfitMargin
        		, pricingEngineMaxSignalSkew, pricingEngineQuoteSize
        		, noOfThreads);
    }

    public void startSimulation() {
    	log.info("Booting Market Making Ecosystem...");

        // 1. Start the recurring Market Tick immediately.
        // LPs start quoting and the book begins to fill.
    	threadPool.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
        
        log.info("Price discovery phase initiated. Waiting for order book to stabilize...");

        // 2. Schedule Takers to start after a 5-second warm-up delay

        threadPool.schedule(() -> {
            log.info("Warm-up complete. Launching Liquidity Taker activities.");
            startLTs();
        }, 5, TimeUnit.SECONDS);

    }

    
    private int generateLBidAskSize() {
    	return lpBidAskSizeFloor + random.nextInt(lpBidAskSizeFloor);
    }
    
    private void tick() {
        try {
        	
        	double move = (random.nextDouble() - 0.5) * volatility;

        	// --- Event-Driven Shock Simulation ---
        	// 0.1% chance of a "Fat Tail" outlier event
        	if (random.nextDouble() < 0.001) { 
        	    // Multiply base volatility by a factor of 3 to 5 (300% - 500%)
        	    double shockMultiplier = 3.0 + (random.nextDouble() * 2.0);
        	    double shockDirection = random.nextBoolean() ? 1.0 : -1.0;
        	    
        	    move = shockDirection * (volatility * shockMultiplier);
        	    
        	    log.warn("MARKET SHOCK TRIGGERED: Move is {}x standard volatility ({} absolute move)", 
        	             String.format("%.2f", shockMultiplier), 
        	             String.format("%.4f", move));
        	}

        	refPrice += move;

            // Update LPs
            for (int i = 1; i <= numberOfLPs; i++) {
                final String lpId = "LP_" + i;
                threadPool.submit(() -> {
                	
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
	                    
	                    if (lpLatencyMillisec > 0)
	                    	Thread.sleep(lpLatencyMillisec);
	                }                		
                	catch (InterruptedException ie) {
                		log.warn("Thread interrupted unexpectedly", ie);
                	}
                });
            }

        } catch (Exception e) {
            log.error("Simulation error", e);
        }
    }
    
    
    private void startLTs() {
        for (int i = 1; i <= numberOfLTs; i++) {
            final String takerId = "LT_" + i;
            threadPool.submit(() -> {
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
        long sequence = ringBuffer.next(); // Claim the slot
        try {
            OrderEntryEvent event = ringBuffer.get(sequence);
            event.reset(); // Ensure fields are cleared
            
            // Use local variables to determine values first
            String side = random.nextBoolean() ? "BUY" : "SELL";
            int quantity = 10 + random.nextInt(pricingEngineQuoteSize - 10);
            double multiplier = "BUY".equals(side) ? (1 + ltpremiumratio) : (1 - ltpremiumratio);
            double limitPrice = refPrice * multiplier;

            // Now populate the event
            event.setSenderId(takerId);
            event.setSide(side);
            event.setQty(quantity);
            event.setLimit(limitPrice);
            
            long now = System.nanoTime();
            event.setTransactTime(now);
            event.setParentId(now);
        } finally {
            ringBuffer.publish(sequence); // Commit the slot
        }
    }


    public void stopSimulation() {
        disruptor.shutdown();
        threadPool.shutdown();
    }

    // Accessors for External Wiring (Gateway)
    public void addLPQuoteListener(LPQuoteListener l) { this.lpListeners.add(l); }
    public void addPricingListener(PricingListener l) { this.engine.addListener(l); }
    public void addSignalListener(SignalListener l) { this.signalEmitter.addListener(l); }
    public void addBookListener(OrderBookUpdateListener l) { this.aggregator.addBookListener(l); }
    public void addTcaListener(TCAUpdateListener l) { this.tcaManager.addListener(l); }
}