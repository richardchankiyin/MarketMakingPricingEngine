package com.richard.marketmakingpricing;

import java.util.function.Consumer;

public class SignalEmitter {
    private volatile double currentSignal = 0.0;
    private Consumer<Double> listener;

    public void setListener(Consumer<Double> listener) {
        this.listener = listener;
    }

    /**
     * Called when the external Python Signal Generator or 
     * the OMS (Inventory) pushes an update.
     */
    public void updateSignal(double rawAlpha, double inventoryLevel) {
        // Logic: Signal is bullish alpha minus a penalty for being long (inventory)
        // Adjust the coefficients (0.1) based on your simulation needs
        this.currentSignal = rawAlpha - (inventoryLevel * 0.001);
        
        if (listener != null) {
            listener.accept(currentSignal);
        }
    }

    public double getCurrentSignal() {
        return currentSignal;
    }
}