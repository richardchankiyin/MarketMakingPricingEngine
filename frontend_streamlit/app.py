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
                            # Store the raw nested maps: {Price: {LP: Size}}
                            st.session_state.full_book_data = {
                                'bids': data.get('bids', {}),
                                'asks': data.get('asks', {})
                            }
                    except: continue
        except Exception as e:
            logger.error(f"Stream {state_key} error: {e}")
            time.sleep(2)

# 4. Thread Launcher
if not st.session_state.threads_initialized:
    endpoints = [
        ('http://127.0.0.1:7070/priceengine', 'price_data'),
        ('http://127.0.0.1:7070/signal', 'signal'),
        ('http://127.0.0.1:7070/lpquote', 'lp'),
        ('http://127.0.0.1:7070/fullbook', 'full_book')
    ]
    for url, key in endpoints:
        t = threading.Thread(target=sse_worker, args=(url, key), daemon=True)
        add_script_run_ctx(t)
        t.start()
    st.session_state.threads_initialized = True

# 5. Helper for Book UI
def render_book_side(data_dict, side_name):
    """Flattens nested JSON: {Price: {LP: Size}} into a Sorted DataFrame."""
    rows = []
    for price, lps in data_dict.items():
        for lp, size in lps.items():
            rows.append({
                "Price": float(price),
                "LP": lp,
                "Size": size
            })
    
    df = pd.DataFrame(rows)
    if not df.empty:
        # Bids sorted descending (Highest price first), Asks sorted ascending (Lowest price first)
        df = df.sort_values(by="Price", ascending=(side_name == "Asks"))
        st.dataframe(df, use_container_width=True, hide_index=True)
    else:
        st.caption("No liquidity in book")

# 6. UI Styling Logic
def highlight_recent_updates(row):
    duration = 0.5
    now = datetime.now()
    diff = (now - row['_raw_time']).total_seconds()
    if diff < duration:
        return ['background-color: #990000; color: white'] * len(row)
    return [''] * len(row)

# 7. UI Layout
st.set_page_config(page_title="L1 & Full Book Monitor", layout="wide")
st.title("🛡️ Market Making Gateway Monitor")

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
