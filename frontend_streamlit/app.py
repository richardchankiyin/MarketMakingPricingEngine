import streamlit as st
import pandas as pd
import json
import threading
import requests
import time
import logging
from datetime import datetime
from sseclient import SSEClient
from streamlit.runtime.scriptrunner import add_script_run_ctx

# 1. Logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
logger = logging.getLogger(__name__)

# 2. State Initialization
if 'price_history' not in st.session_state:
    st.session_state.price_history = pd.DataFrame(columns=['time', 'bid', 'bidSize', 'ask', 'askSize', 'mid'])
if 'signal_val' not in st.session_state:
    st.session_state.signal_val = 0.0
if 'lp_latest_map' not in st.session_state:
    st.session_state.lp_latest_map = {} 
if 'full_book_data' not in st.session_state:
    st.session_state.full_book_data = {'bids': {}, 'asks': {}}
if 'threads_initialized' not in st.session_state:
    st.session_state.threads_initialized = False

# --- NEW TCA STATE ---
if 'pnl_history' not in st.session_state:
    st.session_state.pnl_history = pd.DataFrame(columns=['time', 'pnl'])
if 'client_metrics' not in st.session_state:
    st.session_state.client_metrics = {}
if 'firm_metrics' not in st.session_state:
    st.session_state.firm_metrics = {'totalOrders': 0, 'totalFills': 0, 'totalPnL': 0.0, 'fillRate': 0.0}

# 3. Worker
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
                            new_entry = {
                                'time': timestamp_str,
                                'bid': data.get('bid', 0.0), 'bidSize': data.get('bidSize', 0),
                                'ask': data.get('ask', 0.0), 'askSize': data.get('askSize', 0),
                                'mid': data.get('mid', 0.0)
                            }
                            new_row = pd.DataFrame([new_entry])
                            st.session_state.price_history = pd.concat([st.session_state.price_history, new_row]).tail(15)
                            
                        elif state_key == 'signal':
                            st.session_state.signal_val = data.get('signal', 0.0)
                            
                        elif state_key == 'lp':
                            lp_id = data.get('lpId', 'Unknown')
                            st.session_state.lp_latest_map[lp_id] = {
                                'LP ID': lp_id,
                                'Last Update': timestamp_str,
                                '_raw_time': now, 
                                'Ref Price': data.get('refPrice', 0.0),
                                'Bid': data.get('bid', 0.0),
                                'BidSize': data.get('bidSize', 0),
                                'Ask': data.get('ask', 0.0),
                                'AskSize': data.get('askSize', 0)
                            }

                        elif state_key == 'full_book':
                            st.session_state.full_book_data = {
                                'bids': data.get('bids', {}),
                                'asks': data.get('asks', {})
                            }

                        elif state_key == 'tca':
                            firm = data.get('firmMetrics', {})
                            st.session_state.firm_metrics = firm
                            st.session_state.client_metrics = data.get('allClientMetrics', {})
                            
                            # Update PnL History for live curve
                            new_pnl = {'time': timestamp_str, 'pnl': firm.get('totalPnL', 0.0)}
                            st.session_state.pnl_history = pd.concat([
                                st.session_state.pnl_history, 
                                pd.DataFrame([new_pnl])
                            ]).tail(50) # Buffer last 50 updates
                            
                    except Exception as e:
                        continue
        except Exception as e:
            logger.error(f"Stream {state_key} error: {e}")
            time.sleep(2)

# 4. Thread Launcher
if not st.session_state.threads_initialized:
    endpoints = [
        ('http://127.0.0.1:7070/priceengine', 'price_data'),
        ('http://127.0.0.1:7070/signal', 'signal'),
        ('http://127.0.0.1:7070/lpquote', 'lp'),
        ('http://127.0.0.1:7070/fullbook', 'full_book'),
        ('http://127.0.0.1:7070/tca', 'tca')
    ]
    for url, key in endpoints:
        t = threading.Thread(target=sse_worker, args=(url, key), daemon=True)
        add_script_run_ctx(t)
        t.start()
    st.session_state.threads_initialized = True

# 5. Helper Functions
def render_book_side(data_dict, side_name):
    rows = []
    for price, lps in data_dict.items():
        for lp, size in lps.items():
            rows.append({"Price": float(price), "LP": lp, "Size": size})
    df = pd.DataFrame(rows)
    if not df.empty:
        df = df.sort_values(by="Price", ascending=(side_name == "Asks"))
        st.dataframe(df, use_container_width=True, hide_index=True)
    else:
        st.caption("No liquidity in book")

def highlight_recent_updates(row):
    duration = 0.5
    now = datetime.now()
    diff = (now - row['_raw_time']).total_seconds()
    if diff < duration:
        return ['background-color: #990000; color: white'] * len(row)
    return [''] * len(row)

# 7. UI Layout
st.set_page_config(page_title="OMS Gateway & Strategy Monitor", layout="wide")
st.title("🛡️ Market Making Gateway Monitor")

# --- TCA & STRATEGY SECTION ---
st.header("📈 Strategy Performance & TCA")
f_m = st.session_state.firm_metrics
m_col1, m_col2, m_col3, m_col4 = st.columns(4)
m_col1.metric("Firm Total PnL", f"{f_m['totalPnL']:.4f}")
m_col2.metric("Fill Rate", f"{f_m['fillRate']*100:.2f}%")
m_col3.metric("Total Fills", f_m['totalFills'])
m_col4.metric("Total Orders", f_m['totalOrders'])

chart_col, reject_col = st.columns([2, 1])
with chart_col:
    st.subheader("Strategy PnL Curve")
    if not st.session_state.pnl_history.empty:
        st.line_chart(st.session_state.pnl_history.set_index('time'))

with reject_col:
    st.subheader("Reject Distribution")
    if st.session_state.client_metrics:
        # Aggregate rejectReasonCounts across all clients for firm-wide view
        df_clients = pd.DataFrame.from_dict(st.session_state.client_metrics, orient='index')
        if 'rejectReasonCounts' in df_clients.columns:
            reject_reasons = df_clients['rejectReasonCounts'].apply(pd.Series).sum().to_frame().T
            st.bar_chart(reject_reasons.T)

st.subheader("Client Statistics & Spread Capture")
if st.session_state.client_metrics:
    client_stats_df = pd.DataFrame.from_dict(st.session_state.client_metrics, orient='index')
    # Filter for display columns
    display_cols = ['totalPnL', 'averageBps', 'fillCount', 'rejectCount', 'toxic']
    available_cols = [c for c in display_cols if c in client_stats_df.columns]
    st.dataframe(client_stats_df[available_cols], use_container_width=True)

st.divider()

# --- FULL BOOK SECTION ---
st.subheader("📊 Global Aggregated Order Book (Full Depth)")
book_col1, book_col2 = st.columns(2)
with book_col1:
    st.markdown("### 🟢 Bids")
    render_book_side(st.session_state.full_book_data['bids'], "Bids")
with book_col2:
    st.markdown("### 🔴 Asks")
    render_book_side(st.session_state.full_book_data['asks'], "Asks")

st.divider()

# --- LP SECTION ---
st.subheader("Latest LP Quotes (Level 1)")
if st.session_state.lp_latest_map:
    lp_df = pd.DataFrame(st.session_state.lp_latest_map.values())
    lp_df = lp_df.sort_values(by='LP ID')
    styled_lp_df = lp_df.style.apply(highlight_recent_updates, axis=1)
    st.dataframe(
        styled_lp_df, 
        use_container_width=True, 
        hide_index=True,
        column_order=['LP ID', 'Last Update', 'Ref Price', 'Bid', 'BidSize', 'Ask', 'AskSize']
    )
else:
    st.info("Awaiting LP Quotes...")

st.divider()

# --- INTERNAL SECTION ---
col_left, col_right = st.columns([2, 1])
with col_left:
    st.subheader("Internal Pricing Tape")
    st.dataframe(st.session_state.price_history.copy(), use_container_width=True, hide_index=True)

with col_right:
    st.subheader("Signal Engine")
    st.metric("Alpha Skew", f"{st.session_state.signal_val:.4f}")

# 8. Rapid Refresh
time.sleep(0.3) 
st.rerun()
