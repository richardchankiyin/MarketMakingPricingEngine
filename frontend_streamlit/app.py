import os
import streamlit as st
import pandas as pd
import json
import threading
import requests
import time
import logging
import plotly.graph_objects as go
from datetime import datetime
from sseclient import SSEClient
from streamlit.runtime.scriptrunner import add_script_run_ctx

# Get the host from environment, or default to localhost for local testing
backend_host = os.getenv("BACKEND_ADDR", "127.0.0.1:7070")

# 1. Logging Setup
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
logger = logging.getLogger(__name__)

# 2. Session State Initialization
if 'price_history' not in st.session_state:
    st.session_state.price_history = pd.DataFrame(columns=['time', 'bid', 'bidSize', 'ask', 'askSize', 'mid'])
if 'signal_history' not in st.session_state:
    st.session_state.signal_history = pd.DataFrame(columns=['time', 'signal'])
if 'lp_latest_map' not in st.session_state:
    st.session_state.lp_latest_map = {} 
if 'full_book_data' not in st.session_state:
    st.session_state.full_book_data = {'bids': {}, 'asks': {}}
if 'ohlc_data' not in st.session_state:
    st.session_state.ohlc_data = pd.DataFrame(columns=['time', 'open', 'high', 'low', 'close'])
if 'pnl_history' not in st.session_state:
    st.session_state.pnl_history = pd.DataFrame(columns=['time', 'pnl'])
if 'client_metrics' not in st.session_state:
    st.session_state.client_metrics = {}
if 'firm_metrics' not in st.session_state:
    st.session_state.firm_metrics = {'totalOrders': 0, 'totalFills': 0, 'totalPnL': 0.0, 'fillRate': 0.0}
if 'threads_initialized' not in st.session_state:
    st.session_state.threads_initialized = False

# 3. SSE Worker Function
def sse_worker(url, state_key):
    headers = {"Accept": "text/event-stream", "Cache-Control": "no-cache"}
    while True:
        try:
            response = requests.get(url, headers=headers, stream=True, timeout=(5, None))
            client = SSEClient(response)
            for msg in client.events():
                if msg.data and msg.data.startswith('{'):
                    try:
                        data = json.loads(msg.data)
                        now = datetime.now()
                        timestamp_str = now.strftime('%H:%M:%S.%f')[:-3]
                        
                        if state_key == 'price_data':
                            mid = data.get('mid', 0.0)
                            new_entry = {
                                'time': timestamp_str,
                                'bid': data.get('bid', 0.0), 'bidSize': data.get('bidSize', 0),
                                'ask': data.get('ask', 0.0), 'askSize': data.get('askSize', 0),
                                'mid': mid
                            }
                            st.session_state.price_history = pd.concat([st.session_state.price_history, pd.DataFrame([new_entry])], ignore_index=True).tail(15)
                            
                            # 1-minute OHLC Aggregation
                            current_min = now.replace(second=0, microsecond=0)
                            if not st.session_state.ohlc_data.empty and st.session_state.ohlc_data['time'].iloc[-1] == current_min:
                                idx = st.session_state.ohlc_data.index[-1]
                                st.session_state.ohlc_data.at[idx, 'high'] = max(st.session_state.ohlc_data.at[idx, 'high'], mid)
                                st.session_state.ohlc_data.at[idx, 'low'] = min(st.session_state.ohlc_data.at[idx, 'low'], mid)
                                st.session_state.ohlc_data.at[idx, 'close'] = mid
                            else:
                                new_candle = {'time': current_min, 'open': mid, 'high': mid, 'low': mid, 'close': mid}
                                st.session_state.ohlc_data = pd.concat([st.session_state.ohlc_data, pd.DataFrame([new_candle])], ignore_index=True).tail(20)

                        elif state_key == 'signal':
                            sig_entry = {'time': timestamp_str, 'signal': data.get('signal', 0.0)}
                            st.session_state.signal_history = pd.concat([st.session_state.signal_history, pd.DataFrame([sig_entry])], ignore_index=True).tail(30)
                            
                        elif state_key == 'lp':
                            lp_id = data.get('lpId', 'Unknown')
                            st.session_state.lp_latest_map[lp_id] = {
                                'LP ID': lp_id, 'Last Update': timestamp_str, '_raw_time': now, 
                                'Ref Price': data.get('refPrice', 0.0), 'Bid': data.get('bid', 0.0),
                                'BidSize': data.get('bidSize', 0), 'Ask': data.get('ask', 0.0), 'AskSize': data.get('askSize', 0)
                            }

                        elif state_key == 'full_book':
                            st.session_state.full_book_data = {'bids': data.get('bids', {}), 'asks': data.get('asks', {})}

                        elif state_key == 'tca':
                            firm = data.get('firmMetrics', {})
                            st.session_state.firm_metrics = firm
                            st.session_state.client_metrics = data.get('allClientMetrics', {})
                            new_pnl = {'time': timestamp_str, 'pnl': firm.get('totalPnL', 0.0)}
                            st.session_state.pnl_history = pd.concat([st.session_state.pnl_history, pd.DataFrame([new_pnl])]).tail(50)
                            
                    except Exception: continue
        except Exception as e:
            logger.error(f"Stream {state_key} error: {e}")
            time.sleep(2)

# 4. Thread Launcher
if not st.session_state.threads_initialized:
    endpoints = [
        (f'http://{backend_host}/priceengine', 'price_data'),
        (f'http://{backend_host}/signal', 'signal'),
        (f'http://{backend_host}/lpquote', 'lp'),
        (f'http://{backend_host}/fullbook', 'full_book'),
        (f'http://{backend_host}/tca', 'tca')
    ]
    for url, key in endpoints:
        t = threading.Thread(target=sse_worker, args=(url, key), daemon=True)
        add_script_run_ctx(t)
        t.start()
    st.session_state.threads_initialized = True

# 5. UI Helpers
def render_book_side(data_dict, side_name):
    rows = []
    for price, lps in data_dict.items():
        for lp, size in lps.items():
            rows.append({"Price": float(price), "LP": lp, "Size": size})
    df = pd.DataFrame(rows)
    if not df.empty:
        df = df.sort_values(by="Price", ascending=(side_name == "Asks"))
        st.dataframe(df, use_container_width=True, hide_index=True)
    else: st.caption("No liquidity")

def highlight_recent_updates(row):
    if (datetime.now() - row['_raw_time']).total_seconds() < 0.5:
        return ['background-color: #990000; color: white'] * len(row)
    return [''] * len(row)

# 6. UI Main Layout
st.set_page_config(page_title="Market Making Platform Monitor", layout="wide")
st.title("🛡️ Market Making Platform Monitor")

# --- ROW 1: STRATEGY PERFORMANCE & TCA ---
st.header("📈 Strategy Performance & TCA")
f_m = st.session_state.firm_metrics
m1, m2, m3, m4 = st.columns(4)
m1.metric("Firm Total PnL", f"{f_m['totalPnL']:.4f}")
m2.metric("Fill Rate", f"{f_m['fillRate']*100:.2f}%")
m3.metric("Total Fills", f_m['totalFills'])
m4.metric("Total Orders", f_m['totalOrders'])

c1, c2 = st.columns([2, 1])
with c1:
    st.subheader("Strategy PnL Curve")
    if not st.session_state.pnl_history.empty:
        st.line_chart(st.session_state.pnl_history.set_index('time'))

with c2:
    st.subheader("Reject Reason Distribution")
    if st.session_state.client_metrics:
        df_clients = pd.DataFrame.from_dict(st.session_state.client_metrics, orient='index')
        if 'rejectReasonCounts' in df_clients.columns:
            # 1. Prepare Data
            raw_counts = df_clients['rejectReasonCounts'].apply(pd.Series).sum()
            reason_map = {
                "101": "101: Price/Size",
                "102": "102: Liquidity",
                "999": "999: Unknown"
            }
            labeled_counts = raw_counts.rename(index=lambda x: reason_map.get(str(x), f"{x}: Other"))
            
            # 2. Split column c2 into sub-columns to remove vertical blank space
            chart_sub, info_sub = st.columns([1, 1])
            
            with chart_sub:
                st.bar_chart(labeled_counts, height=200)
            
            with info_sub:
                st.markdown("""
                **Legend:**
                - **101**: Limit price outside spread or qty too large.
                - **102**: LPs have insufficient depth to hedge.
                - **999**: Internal/Unknown error.
                """)


st.subheader("Client Statistics & Spread Capture")
if st.session_state.client_metrics:
    client_stats_df = pd.DataFrame.from_dict(st.session_state.client_metrics, orient='index')
    cols = ['totalPnL', 'averageBps', 'fillCount', 'rejectCount', 'toxic']
    st.dataframe(client_stats_df[[c for c in cols if c in client_stats_df.columns]], use_container_width=True)

st.divider()

# --- ROW 2: MARKET DYNAMICS (QUOTES, SIGNAL, CANDLES) ---
st.header("🔍 Market Dynamics")
r2_col1, r2_col2, r2_col3 = st.columns([1.2, 1, 1.2])

with r2_col1:
    st.subheader("Our Offering - Bid / Ask Quote")
    st.dataframe(st.session_state.price_history.copy(), use_container_width=True, hide_index=True)

with r2_col2:
    st.subheader("Alpha Skew Signal")
    if not st.session_state.signal_history.empty:
        st.line_chart(st.session_state.signal_history.set_index('time'))
        st.caption("💡 **+ve (Positive):** Bullish Bias (Buying Pressure) | **-ve (Negative):** Bearish Bias (Selling Pressure)")

with r2_col3:
    st.subheader("Mid Price (1m Candle)")
    if not st.session_state.ohlc_data.empty:
        fig = go.Figure(data=[go.Candlestick(
            x=st.session_state.ohlc_data['time'],
            open=st.session_state.ohlc_data['open'],
            high=st.session_state.ohlc_data['high'],
            low=st.session_state.ohlc_data['low'],
            close=st.session_state.ohlc_data['close']
        )])
        fig.update_layout(xaxis_rangeslider_visible=False, margin=dict(l=20, r=20, t=20, b=20), height=300)
        st.plotly_chart(fig, use_container_width=True)

st.divider()

# --- ROW 3: LIQUIDITY DEPTH (BOOK & LP QUOTES) ---
st.header("🧱 Liquidity Depth")
r3_col1, r3_col2 = st.columns([2, 1.5])

with r3_col1:
    st.subheader("Global Aggregated Order Book (Full Depth)")
    b_col1, b_col2 = st.columns(2)
    with b_col1:
        st.markdown("### 🟢 Bids")
        render_book_side(st.session_state.full_book_data['bids'], "Bids")
    with b_col2:
        st.markdown("### 🔴 Asks")
        render_book_side(st.session_state.full_book_data['asks'], "Asks")

with r3_col2:
    st.subheader("Latest LP Quotes (Level 1)")
    if st.session_state.lp_latest_map:
        lp_df = pd.DataFrame(st.session_state.lp_latest_map.values()).sort_values(by='LP ID')
        st.dataframe(lp_df.style.apply(highlight_recent_updates, axis=1), use_container_width=True, hide_index=True,
                     column_order=['LP ID', 'Last Update', 'Ref Price', 'Bid', 'BidSize', 'Ask', 'AskSize'])
    else: st.info("Awaiting LP Quotes...")

# 8. UI Refresh
time.sleep(0.3) 
st.rerun()
