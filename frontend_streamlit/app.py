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
# Changed to a dict to store multiple LPs by their ID
if 'lp_latest_map' not in st.session_state:
    st.session_state.lp_latest_map = {} 
if 'threads_initialized' not in st.session_state:
    st.session_state.threads_initialized = False

# 3. Worker (Updated LP logic)
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
                        timestamp = datetime.now().strftime('%H:%M:%S.%f')[:-3]
                        
                        if state_key == 'price_data':
                            new_entry = {
                                'time': timestamp,
                                'bid': data.get('bid', 0.0),
                                'bidSize': data.get('bidSize', 0),
                                'ask': data.get('ask', 0.0),
                                'askSize': data.get('askSize', 0),
                                'mid': data.get('mid', 0.0)
                            }
                            new_row = pd.DataFrame([new_entry])
                            st.session_state.price_history = pd.concat([st.session_state.price_history, new_row]).tail(15)
                            
                        elif state_key == 'signal':
                            st.session_state.signal_val = data.get('signal', 0.0)
                            
                        elif state_key == 'lp':
                            # Store/Update by LP ID
                            lp_id = data.get('lpId', 'Unknown')
                            st.session_state.lp_latest_map[lp_id] = {
                                'LP ID': lp_id,
                                'Last Update': timestamp,
                                'Ref Price': data.get('refPrice', 0.0),
                                'Bid': data.get('bid', 0.0),
                                'BidSize': data.get('bidSize', 0),
                                'Ask': data.get('ask', 0.0),
                                'AskSize': data.get('askSize', 0)
                            }
                    except:
                        continue
        except Exception as e:
            logger.error(f"Stream {state_key} error: {e}")
            time.sleep(2)

# 4. Thread Launcher
if not st.session_state.threads_initialized:
    endpoints = [
        ('http://127.0.0.1:7070/priceengine', 'price_data'),
        ('http://127.0.0.1:7070/signal', 'signal'),
        ('http://127.0.0.1:7070/lpquote', 'lp')
    ]
    for url, key in endpoints:
        t = threading.Thread(target=sse_worker, args=(url, key), daemon=True)
        add_script_run_ctx(t)
        t.start()
    st.session_state.threads_initialized = True

# 5. UI Layout
st.set_page_config(page_title="LP & Pricing Monitor", layout="wide")
st.title("🛡️ LP Liquidity & Pricing Monitor")

# --- SECTION A: LP Latest Quotes Table ---
st.subheader("Latest LP Quotes (Level 1)")
if st.session_state.lp_latest_map:
    # Convert the map to a DataFrame for tabular display
    lp_df = pd.DataFrame(st.session_state.lp_latest_map.values())
    st.dataframe(lp_df, use_container_width=True, hide_index=True)
else:
    st.info("Awaiting LP Quotes...")

st.divider()

# --- SECTION B: Internal Pricing & Signal ---
col_left, col_right = st.columns([2, 1])

with col_left:
    st.subheader("Internal Price Engine (Audit Log)")
    prices_snap = st.session_state.price_history.copy()
    st.dataframe(prices_snap, use_container_width=True, hide_index=True)

with col_right:
    st.subheader("Metrics")
    st.metric("Alpha Signal Skew", f"{st.session_state.signal_val:.4f}")
    st.write(f"Active LPs: {len(st.session_state.lp_latest_map)}")

# 6. Refresh
time.sleep(1)
st.rerun()
