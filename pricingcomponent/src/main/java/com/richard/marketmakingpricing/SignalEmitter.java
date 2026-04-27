package com.richard.marketmakingpricing;

import java.util.concurrent.atomic.AtomicLong;

public class SignalEmitter implements MarketUpdateListener {

    private final AtomicLong lastSignalBits = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private final double changeThreshold;
    private SignalListener listener;

    /**
     * Default constructor with 0.001 threshold.
     */
    public SignalEmitter() {
        this(0.001);
    }

    /**
     * @param changeThreshold Sensitivity of the signal emission.
     */
    public SignalEmitter(double changeThreshold) {
        this.changeThreshold = changeThreshold;
    }

    public void setListener(SignalListener listener) {
        this.listener = listener;
    }

    @Override
    public void onBookUpdate(double bid, int bSize, double ask, int aSize, double vwapBid, double vwapAsk) {
        if (vwapBid <= 0 || vwapAsk <= 0 || bid >= ask) return;

        // Internal Market Mid calculation
        double marketMid = (bid + ask) / 2.0;

        // Urgency relative to internal market mid
        double bidUrgency = marketMid - vwapBid;
        double askUrgency = vwapAsk - marketMid;

        // Normalized skew: (AskDist - BidDist) / Mid
        double rawSkew = (askUrgency - bidUrgency) / marketMid;
        
        // Final Signal: Bid Aggression (low urgency) = Bearish (-1)
        double newSignal = Math.max(-1.0, Math.min(1.0, -rawSkew * 1000.0));

        updateAtomicSignal(newSignal);
    }

    private void updateAtomicSignal(double newSignal) {
        while (true) {
            long currentBits = lastSignalBits.get();
            double previous = Double.longBitsToDouble(currentBits);

            // Using the threshold passed via constructor
            if (Math.abs(newSignal - previous) < changeThreshold) break;

            if (lastSignalBits.compareAndSet(currentBits, Double.doubleToRawLongBits(newSignal))) {
                if (listener != null) {
                    listener.onSignalChange(newSignal);
                }
                break;
            }
        }
    }

    public double getCurrentSignal() {
        return Double.longBitsToDouble(lastSignalBits.get());
    }
}