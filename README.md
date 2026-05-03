
---

# 🛡️ High-Frequency Market Making & Pricing Engine

A low-latency market-making simulator built with **Java 21**, the **LMAX Disruptor**, and **Javalin**. This platform simulates the complete lifecycle of a quantitative trading operation—from synthetic liquidity generation to toxic flow defense and real-time TCA (Transaction Cost Analysis).

## 🏗️ System Architecture

The platform is architected with a strict separation of concerns between market data propagation and the order execution pipeline to ensure deterministic performance.

```mermaid
graph TD
    subgraph Market_Data_Pipeline_Northbound
        MG1[Market Generator] --> PA[Pricing Aggregator]
        PA --> SE[Signal Emitter]
        SE --> PE[Pricing Engine]
        PE --> OMS_D[OMS Discovery]
    end

    subgraph Execution_Pipeline_Southbound
        MG2[Market Generator Takers] --> DIS[LMAX Disruptor Ring Buffer]
        DIS --> OMS_E[OMS Execution]
        OMS_E --> TCA[TCA Manager]
        OMS_E --> HEDGE[Hedging Logic]
    end

    subgraph Gateway_Layer
        OMS_D --> GS[Gateway Service]
        TCA --> GS
        GS --> SSE[SSE Publisher]
    end

    subgraph Visualization
        SSE --> ST[Streamlit Dashboard]
        ST --> UI[Plotly Charts / OHLC]
    end

    style DIS fill:#f96,stroke:#333,stroke-width:2px
    style GS fill:#69f,stroke:#333,stroke-width:2px
    style ST fill:#00CC96,stroke:#333,stroke-width:2px
```

### 1. Market Data & Pricing Pipeline (Northbound)
*   **Market Generator**: Simulates 12+ Liquidity Providers (LPs) generating thousands of price updates per second[cite: 1].
*   **Pricing Aggregator**: Consolidates fragmented LP quotes into a unified Top-of-Book and Full-Depth view[cite: 1].
*   **Signal Emitter**: Analyzes order book imbalance and price velocity to generate an **Alpha Skew** (Bullish/Bearish bias)[cite: 1].
*   **Pricing Engine**: Combines the Aggregated Price with the Alpha Skew and a configurable `MIN_PROFIT_MARGIN` to produce live Bid/Ask quotes[cite: 1].

### 2. Execution & Risk Pipeline (Southbound)
*   **Market Generator (Takers)**: Simulates 15+ Liquidity Takers (LTs) attacking MM quotes based on premium and latency[cite: 1].
*   **Disruptor (Ring Buffer)**: Acts as the high-speed sequencer, ensuring all trade attempts are processed in strict temporal order without thread contention[cite: 1].
*   **OMS (Order Management System)**: Validates trades against the live book, executes fills, and triggers immediate hedging logic[cite: 1].
*   **TCA Manager**: Calculates real-time metrics including **Fill Rate**, **Slippage**, and **Total PnL**[cite: 1].

---

## 📊 Market Microstructure Scenarios
This engine demonstrates specific quantitative trading regimes via pre-configured Docker profiles:

*   **Scenario A: The "Toxic Arbitrage"**: High Volatility (0.08) + 15 aggressive Takers. Demonstrates the "picked off" effect where the MM suffers from stale quote arbitrage[cite: 1].
*   **Scenario B: High-Performance Execution**: Memory-pinned JVM (`-Xms4g`), NUMA optimization, and Generative ZGC ensuring sub-millisecond GC pauses[cite: 1].

## ⚡ Technical Highlights

| Component | Technology | Performance Benefit |
| :--- | :--- | :--- |
| **Concurrency** | LMAX Disruptor | Mechanical sympathy; avoids traditional lock overhead[cite: 1]. |
| **Runtime** | Java 21 (ZGC) | Generative ZGC keeps pause times < 1ms[cite: 1]. |
| **I/O** | Virtual Threads | Scales to hundreds of UI sessions without thread starvation[cite: 1]. |
| **Frontend** | Streamlit + Plotly | Optimized Pandas rendering with `ignore_index=True` for stability[cite: 1]. |

## 🚀 Getting Started

```bash
# Start the standard simulation
docker-compose up --build

# Run the 'Toxic Arbitrage' stress-test scenario
docker-compose -f docker-compose.yml -f deploy_profiles/docker-compose.toxic.yml up
```

### 🙏 Acknowledgments
Developed in collaboration with **Gemini (Google AI)** for architecture design and performance optimization.
