#!/bin/bash

# Configuration
LOG_DIR="./logs"
LOG_PATTERN="orders.log*"

echo "------------------------------------------------"
echo "Market Making Engine: OMS Performance Audit"
echo "Target: $LOG_DIR/$LOG_PATTERN"
echo "------------------------------------------------"

# Check if log directory exists
if [ ! -d "$LOG_DIR" ]; then
    echo "Error: Log directory $LOG_DIR not found."
    exit 1
fi

# Run the optimized AWK analysis
zgrep "Time Elapsed in μs" $LOG_DIR/$LOG_PATTERN 2>/dev/null | awk '{
    split($0, a, "Time Elapsed in μs ");
    gsub(/[^0-9]/, "", a[2]);
    val = a[2] + 0;

    if (val > 0) {
        sum += val; count++;
        if (val > max) max = val;
        if (count == 1 || val < min) min = val;
    }
} 
END {
    if (count > 0) {
        printf "Total Orders Processed : %d\n", count;
        printf "Average Execution Time : %.2f μs\n", sum/count;
        printf "Fastest Execution (Min): %d μs\n", min;
        printf "Slowest Execution (Max): %d μs\n", max;
        printf "------------------------------------------------\n";
        
        if (max > 500000) {
            printf "Note: High Max latency detected. This is typically\n";
            printf "attributed to JVM JIT compilation during cold start.\n";
        }
    } else {
        print "No latency data found in logs.";
    }
}'
