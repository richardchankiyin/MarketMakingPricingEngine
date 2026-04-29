package com.richard.marketmakingpricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

public class MarketGenerator {
	private final List<LPQuoteListener> lpListeners = new CopyOnWriteArrayList<>();
    private static final Logger log = LoggerFactory.getLogger(MarketGenerator.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService lpPool = Executors.newFixedThreadPool(12);
    private final Random random = new Random();

    private final PriceAggregator aggregator;
    private final SignalEmitter signalEmitter;
    private final PricingEngine engine;

    private double refPrice = 100.00;
    private final double vol = 0.02;

    public MarketGenerator(PriceAggregator aggregator, SignalEmitter signalEmitter, PricingEngine engine) {
    	this.aggregator = aggregator;
    	this.signalEmitter = signalEmitter;
    	this.engine = engine;

        this.aggregator.addListener(this.signalEmitter);
        this.aggregator.addListener(this.engine);
        
        log.info("MarketGenerator initialized. Mid: {}", refPrice);
    }

    public void addListener(LPQuoteListener listener) {
        this.lpListeners.add(listener);
    }    
    
    //TODO below tick sending to be parameterized
    public void startSimulation() {
        log.info("Starting Market Simulation...");
        scheduler.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
    }

    //TODO below to be refactored to be parameterized
    private void tick() {
        try {
            refPrice += (random.nextDouble() - 0.5) * vol;
            log.debug("Market Mid Move: {}", refPrice);

            for (int i = 1; i <= 12; i++) {
                final String lpId = "LP_" + i;
                lpPool.submit(() -> {
                    double lpSpread = 0.02 + (random.nextDouble() * 0.04);
                    double lpBid = refPrice - (lpSpread / 2.0);
                    double lpAsk = refPrice + (lpSpread / 2.0);
                    int lpSize = 200 + random.nextInt(800);

                    // Logging the LP injection
                    log.trace("{} updated: [{} @ {} | {} @ {}]", 
                              lpId, lpBid, lpSize, lpAsk, lpSize);
                    
                    for (LPQuoteListener l : lpListeners) {
                        l.onLPUpdate(lpId, refPrice, lpBid, lpSize, lpAsk, lpSize);
                    }

                    aggregator.onUpdate(lpId, lpBid, lpSize, lpAsk, lpSize);
                });
            }
        } catch (Exception e) {
            log.error("Critical error in simulation tick", e);
        }
    }

    public void stopSimulation() {
        log.info("Stopping Market simulation...");
        scheduler.shutdown();
        lpPool.shutdown();
        log.info("Market simulation stopped.");
    }

    public void addMarketUpdateListener(MarketUpdateListener l) {
    	if (l != null) {
    		this.aggregator.addListener(l);
    	}
    }
    
    
    public void addPricingListener(PricingListener l) {
    	if (l != null) {
    		this.engine.addListener(l);
    	}
    }
    
    public void addSignalListener(SignalListener l) {
    	if (l != null) {
    		this.signalEmitter.addListener(l);
    	}
    }
    
    public static void main(String[] args) throws InterruptedException {
    	PriceAggregator aggregator = new PriceAggregator();
    	SignalEmitter signalEmitter = new SignalEmitter();
    	
    	MarketGenerator mg = new MarketGenerator(aggregator, signalEmitter, new PricingEngine(aggregator, signalEmitter, 0.01, 0.02, 0.15, 500));
    	// Add this to see the final prices produced by the engine!
    	mg.addMarketUpdateListener((bid, bSize, ask, aSize, vwapBid, vwapAsk)-> {
    		log.info("&&&&&&MarketUpdate bid {} - {} | ask {} - {} | vwap bid {} ask {}", bid, bSize, ask, aSize, vwapBid, vwapAsk);
    	});
    	
    	mg.addPricingListener((bid, bSize, ask, aSize, iBid, iAsk) -> {
    		log.info(">>>>>>PricingEngine QUOTE: Bid {} | Ask {}", bid, ask);
    	});
    	
    	mg.addSignalListener((k)->{
    		log.info("#######Signal Change: {}", k);
    	}); 
        
    	mg.startSimulation();
    	Thread.sleep(30000);
    	mg.stopSimulation();
    }
}