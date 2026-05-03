This README is designed to serve as a high-level technical brief for recruiters and quant leads. It highlights the sophisticated engineering choices that differentiate this engine from a standard trading simulator.

---

# 🛡️ High-Frequency Market Making & Pricing Engine

A production-grade, low-latency market-making simulator built with **Java 21**, the **LMAX Disruptor**, and **Javalin**. This platform simulates the complete lifecycle of a quantitative trading operation—from synthetic liquidity generation to toxic flow defense and real-time TCA (Transaction Cost Analysis).

## 🏗️ System Architecture & Data Flow

The platform is architected with a strict separation of concerns between market data propagation and the order execution pipeline to ensure deterministic performance.



### 1. Market Data & Pricing Pipeline (Northbound)
This flow handles the high-frequency ingestion of liquidity and the derivation of our proprietary "fair value."

*   **Market Generator**: Simulates $12+$ Liquidity Providers (LPs) generating thousands of price updates per second[cite: 1].
*   **Pricing Aggregator**: Consolidates fragmented LP quotes into a unified Top-of-Book and Full-Depth view[cite: 1].
*   **Signal Emitter**: Analyzes order book imbalance and price velocity to generate an **Alpha Skew** (Bullish/Bearish bias)[cite: 1].
*   **Pricing Engine**: Combines the Aggregated Price with the Alpha Skew and a configurable `MIN_PROFIT_MARGIN` to produce live Bid/Ask quotes[cite: 1].
*   **OMS (Discovery)**: Routes the finalized "Market Making" quotes to the gateway for public distribution[cite: 1].

### 2. Execution & Risk Pipeline (Southbound)
This pipeline utilizes a lock-free, event-driven model for high-throughput trade processing.

*   **Market Generator (Takers)**: Simulates $15+$ Liquidity Takers (LTs) attacking MM quotes based on premium and latency[cite: 1].
*   **Disruptor (Ring Buffer)**: Acts as the high-speed sequencer, ensuring all trade attempts are processed in strict temporal order without thread contention[cite: 1].
*   **OMS (Order Management System)**: Validates trades against the live book, executes fills, and triggers immediate hedging logic[cite: 1].
*   **TCA Manager**: Calculates real-time metrics including **Fill Rate**, **Slippage**, and **Total PnL**[cite: 1].

### 3. Gateway & Visualization Layer
*   **Gateway Service**: A Javalin-based server utilizing **Java 21 Virtual Threads** to subscribe to internal engine events (Price, Signal, Trade, TCA)[cite: 1].
*   **SSE Publisher**: Pushes data via **Server-Sent Events (SSE)** to downstream consumers at $300\text{ms}$ intervals[cite: 1].
*   **Streamlit UI**: A Python-based dashboard that consumes SSE streams to render real-time Candlesticks (1m OHLC aggregation), PnL Curves, and Alpha Skew charts[cite: 1].

---

## 📊 Market Microstructure Scenarios
This engine demonstrates specific quantitative trading regimes via pre-configured Docker profiles:

### Scenario A: The "Toxic Arbitrage" (Adverse Selection)
*   **Dynamics**: High Volatility ($0.08$) + $15$ aggressive Takers + $1\text{ms}$ LP Latency[cite: 1].
*   **Observation**: The MM suffers from "Stale Quote Arbitrage," where Takers execute against prices before the engine skews the book.
*   **Quant Metric**: High fill volume coupled with sharply negative PnL—demonstrating the "picked off" effect[cite: 1].

### Scenario B: High-Performance Execution
*   **Dynamics**: Memory-pinned JVM (`-Xms4g`), **NUMA** optimization, and **Transparent Huge Pages**.
*   **Quant Metric**: Sub-millisecond GC pauses using **Generative ZGC**, ensuring consistent engine ticks even under heavy order-flow pressure[cite: 1].

---

## ⚡ Technical Highlights

| Component | Technology | Performance Benefit |
| :--- | :--- | :--- |
| **Concurrency** | LMAX Disruptor | Mechanical sympathy; avoids traditional lock overhead[cite: 1]. |
| **Runtime** | Java 21 (ZGC) | Generative ZGC keeps pause times $< 1\text{ms}$[cite: 1]. |
| **I/O** | Virtual Threads | Scales to hundreds of UI sessions without thread starvation[cite: 1]. |
| **Frontend** | Streamlit + Plotly | Optimized Pandas rendering with `ignore_index=True` for HFT data stability[cite: 1]. |
| **Logging** | Logback + GZ | Asynchronous logging with automatic GZ compression for high-volume audit trails[cite: 1]. |

---

## 🚀 Getting Started

```bash
# Start the standard simulation
docker-compose up --build

# Run the 'Toxic Arbitrage' stress-test scenario
docker-compose -f docker-compose.yml -f deploy_profiles/docker-compose.toxic.yml up
```

### 🙏 Acknowledgments
Developed in collaboration with **Gemini (Google AI)** for architecture design and performance optimization.
