#!/bin/bash

LOG_DIR="./logs"
LOG_PATTERN="applogs.log*" # Adjust if these are in a different log file

echo "------------------------------------------------------------"
echo " Pricing Component Latency Audit"
echo "------------------------------------------------------------"

# Function to process specific component
analyze_component() {
    local component=$1
    local label=$2
    
    zgrep "$component" $LOG_DIR/$LOG_PATTERN 2>/dev/null | awk -v lbl="$label" '{
        split($0, a, "time diff in μs: ");
        val = a[2] + 0;

        if (val > 0) {
            sum += val; count++;
            if (val > max) max = val;
            if (count == 1 || val < min) min = val;
        }
    } 
    END {
        if (count > 0) {
            printf "%-15s | Avg: %10.2f μs | Min: %8d μs | Max: %8d μs | Count: %d\n", 
            lbl, sum/count, min, max, count;
        }
    }'
}

# Run analysis for each requested component
analyze_component "c.r.m.PricingEngine" "PricingEngine"
analyze_component "c.r.m.PriceAggregator" "PriceAggregator"
analyze_component "c.r.m.SignalEmitter" "SignalEmitter"

echo "------------------------------------------------------------"
