import streamlit as st
import pandas as pd
import json
import threading
import requests
import time
import logging
from sseclient import SSEClient
from streamlit.runtime.scriptrunner import add_script_run_ctx

# 1. Logging Setup
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# 2. State Initialization
if 'price_history' not in st.session_state:
    st.session_state.price_history = pd.DataFrame(columns=['bid', 'ask', 'mid'])
if 'signal_val' not in st.session_state:
    st.session_state.signal_val = 0.0
if 'lp_quote' not in st.session_state:
    st.session_state.lp_quote = {}
if 'threads_initialized' not in st.session_state:
    st.session_state.threads_initialized = False

# 3. Worker (Unchanged logic, just feeds the state)
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
                            new_row = pd.DataFrame([data])
                            st.session_state.price_history = pd.concat([st.session_state.price_history, new_row]).tail(10)
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

# 5. UI - Tabular Layout
st.set_page_config(page_title="Data Verify", layout="wide")
st.title("🛡️ Data Verification (Tabular)")

# Snapshots to prevent mid-render mutation
prices_snap = st.session_state.price_history.copy()
lp_snap = st.session_state.lp_quote.copy()
sig_snap = st.session_state.signal_val

col1, col2 = st.columns(2)

with col1:
    st.subheader("Price Engine (Latest 10)")
    st.dataframe(prices_snap, use_container_width=True)

with col2:
    st.subheader("Signal & LP State")
    st.metric("Signal Skew", f"{sig_snap:.4f}")
    st.write("Latest LP Quote JSON:")
    st.json(lp_snap)

# 6. Auto-Refresh
time.sleep(1)
st.rerun()
