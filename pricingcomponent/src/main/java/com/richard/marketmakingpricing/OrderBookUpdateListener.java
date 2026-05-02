package com.richard.marketmakingpricing;

import java.util.Map;
import java.util.NavigableMap;

public interface OrderBookUpdateListener {
    void onFullBookUpdate(
        NavigableMap<Double, Map<String, Integer>> bids, 
        NavigableMap<Double, Map<String, Integer>> asks
    );
}