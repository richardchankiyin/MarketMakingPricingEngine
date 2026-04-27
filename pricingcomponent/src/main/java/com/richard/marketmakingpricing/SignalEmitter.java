package com.richard.marketmakingpricing;

import java.util.concurrent.atomic.AtomicLong;

public class SignalEmitter implements MarketUpdateListener {

    private final AtomicLong lastSignalBits = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private final double changeThreshold;
    private SignalListener listener;

    public SignalEmitter() {
        this(0.001);
    }

    public SignalEmitter(double changeThreshold) {
        this.changeThreshold = changeThreshold;
    }

    public void setListener(SignalListener listener) {
        this.listener = listener;
    }

    @Override
    public void onBookUpdate(double bid, int bSize, double ask, int aSize, double vwapBid, double vwapAsk) {
        // Basic validation to prevent NaN or Infinity
        if (vwapBid <= 0 || vwapAsk <= 0 || bid >= ask) return;

        double marketMid = (bid + ask) / 2.0;
        double bidUrgency = marketMid - vwapBid;
        double askUrgency = vwapAsk - marketMid;

        // Skew is the difference in urgency
        double rawSkew = (askUrgency - bidUrgency) / marketMid;
        
        // Scale by 1000 to make small basis point moves significant
        // Flip sign: Aggressive Bids (low bidUrgency) -> Negative Signal
        double newSignal = -rawSkew * 1000.0;
        
        // Clamp to [-1.0, 1.0]
        if (newSignal > 1.0) newSignal = 1.0;
        if (newSignal < -1.0) newSignal = -1.0;

        updateAtomicSignal(newSignal);
    }

    private void updateAtomicSignal(double newSignal) {
        while (true) {
            long currentBits = lastSignalBits.get();
            double previous = Double.longBitsToDouble(currentBits);

            // If the change is smaller than threshold, suppress update
            if (Math.abs(newSignal - previous) < changeThreshold) {
                break;
            }

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