
---

# 🛡️ High-Frequency Market Making & Pricing Engine

A low-latency market-making simulator built with **Java 21**, the **LMAX Disruptor**, and **Javalin**. This platform simulates the complete lifecycle of a quantitative trading operation—from synthetic liquidity generation to toxic flow defense and real-time TCA (Transaction Cost Analysis).

## 🏗️ System Architecture

The platform is architected with a strict separation of concerns between market data propagation and the order execution pipeline to ensure deterministic performance.

```mermaid
graph TD
    %% Global Styles
    classDef default fill:#f9f9f9,stroke:#333,stroke-width:1px,color:#333,font-family:Arial;
    classDef highSpeed fill:#e1f5fe,stroke:#01579b,stroke-width:2px,color:#01579b,font-weight:bold;
    classDef critical fill:#fff3e0,stroke:#e65100,stroke-width:2px,color:#e65100,font-weight:bold;
    classDef gateway fill:#f3e5f5,stroke:#4a148c,stroke-width:2px,color:#4a148c,font-weight:bold;
    classDef ui fill:#e8f5e9,stroke:#1b5e20,stroke-width:2px,color:#1b5e20,font-weight:bold;

    subgraph Northbound [Market Data & Pricing Pipeline]
        MG1([Market Generator]) --- PA[Pricing Aggregator]
        PA --- SE[Signal Emitter]
        SE --- PE[Pricing Engine]
        PE --- OMS_D[OMS Discovery]
    end

    subgraph Southbound [Execution & Risk Pipeline]
        MG2([Market Generator Takers]) --- DIS[[LMAX Disruptor Ring Buffer]]
        DIS --- OMS_E[OMS Execution]
        OMS_E --- TCA[TCA Manager]
        OMS_E --- HEDGE[Hedging Logic]
    end

    subgraph Distribution [Gateway Layer]
        OMS_D -.-> GS(Gateway Service)
        TCA -.-> GS
        GS -.-> STATE[(Concurrent Master State)]
        STATE -.-> SSE[SSE Publisher]
    end

    subgraph Client [Visualization]
        SSE ==> ST[Streamlit Dashboard]
        ST ==> UI{{Plotly Charts / OHLC}}
    end

    %% Apply Classes
    class DIS critical;
    class GS,STATE gateway;
    class ST,UI ui;
    class PA,SE,PE highSpeed;
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

The engine supports multiple deployment profiles to simulate different market conditions and hardware optimizations[cite: 1]. Use these to test the robustness of the pricing logic and JVM stability.

| Scenario | Profile Name | Key Dynamics | Primary Objective |
| :--- | :--- | :--- | :--- |
| **A** | **Normal Run** | Default liquidity parameters and taker behavior[cite: 1]. | Establish baseline performance and stable PnL tracking[cite: 1]. |
| **B** | **Toxic Arbitrage** | High Volatility ($0.08$) + $15$ Aggressive Takers[cite: 1]. | Observe "Stale Quote Arbitrage" and the impact of adverse selection[cite: 1]. |
| **C** | **High Rejection** | Ultra-low Profit Margin + Low Premium Ratio. | Observe high order rejection rates due to "Price Improvement" failures[cite: 1]. |
| **D** | **High Performance** | JVM Pinned ($-Xms4g$), NUMA aware, & Generative ZGC. | Demonstrate sub-1ms GC pauses and maximized tick-to-trade throughput[cite: 1]. |

> **Note**: Scenarios B, C, and D are triggered by passing specific compose files using the `-f` flag during startup[cite: 1].

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
docker compose up --build

# Scenario B - Run the 'Toxic Arbitrage' stress-test scenario
docker compose down; docker compose -f profiles/docker-compose-toxic.yml  --project-directory . --build up -d

# Scenario C - Run the 'High Rejection' scenario
docker compose down; docker compose -f profiles/docker-compose-rejection.yml --project-directory . --build up -d
```

### 🙏 Acknowledgments
Developed in collaboration with **Gemini (Google AI)** for architecture design and performance optimization.
