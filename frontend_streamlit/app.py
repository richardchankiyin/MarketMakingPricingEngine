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

# 2. State Initialization (Including Sizes)
if 'price_history' not in st.session_state:
    # Adding bidSize and askSize to the schema
    st.session_state.price_history = pd.DataFrame(
        columns=['time', 'bid', 'bidSize', 'ask', 'askSize', 'mid']
    )
if 'signal_val' not in st.session_state:
    st.session_state.signal_val = 0.0
if 'lp_quote' not in st.session_state:
    st.session_state.lp_quote = {}
if 'threads_initialized' not in st.session_state:
    st.session_state.threads_initialized = False

# 3. Worker (Capturing All Fields)
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
                        
                        if state_key == 'price_data':
                            new_entry = {
                                'time': datetime.now().strftime('%H:%M:%S.%f')[:-3],
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
                            st.session_state.lp_quote = data
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
st.set_page_config(page_title="Price Engine Monitor", layout="wide")
st.title("🛡️ Price Engine Dashboard")

# Snapshots
prices_snap = st.session_state.price_history.copy()
lp_snap = st.session_state.lp_quote.copy()

# Row 1: The Table (Hiding the index column)
st.subheader("Internal Price Engine Stream")
st.dataframe(
    prices_snap, 
    use_container_width=True, 
    hide_index=True  # THIS REMOVES THE ODD ZERO COLUMN
)

# Row 2: Diagnostics
st.divider()
c1, c2 = st.columns([1, 1])
with c1:
    st.subheader("Raw LP JSON (Diagnostic)")
    st.json(lp_snap) 
with c2:
    st.subheader("Metrics")
    st.metric("Alpha Signal Skew", f"{st.session_state.signal_val:.4f}")
    if lp_snap:
        st.write(f"**Source LP:** {lp_snap.get('lpId', 'Unknown')}")

# 6. Auto-Refresh
time.sleep(1)
st.rerun()
