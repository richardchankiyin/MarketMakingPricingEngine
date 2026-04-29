import streamlit as st
import pandas as pd
import json
import threading
import requests
import time
import logging
from sseclient import SSEClient
# Correct import path for Streamlit 1.32.0
from streamlit.runtime.scriptrunner import add_script_run_ctx

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# 1. Init State
if 'price_history' not in st.session_state:
    st.session_state.price_history = pd.DataFrame(columns=['bid', 'ask', 'mid'])
if 'signal_val' not in st.session_state:
    st.session_state.signal_val = 0.0
if 'lp_quote' not in st.session_state:
    st.session_state.lp_quote = {}
if 'threads_initialized' not in st.session_state:
    st.session_state.threads_initialized = False

# 2. Worker (No changes needed here, logic is sound)
def sse_worker(url, state_key):
    headers = {"Accept": "text/event-stream", "Cache-Control": "no-cache"}
    try:
        response = requests.get(url, headers=headers, stream=True, timeout=(5, None))
        client = SSEClient(response)
        for msg in client.events():
            if msg.data and msg.data.startswith('{'):
                data = json.loads(msg.data)
                # Now st.session_state will be visible thanks to the context bridge
                if state_key == 'price_data':
                    new_row = pd.DataFrame([{'bid': data['bid'], 'ask': data['ask'], 'mid': data['mid']}])
                    st.session_state.price_history = pd.concat([st.session_state.price_history, new_row]).tail(20)
                elif state_key == 'signal':
                    st.session_state.signal_val = data.get('signal', 0.0)
                elif state_key == 'lp':
                    st.session_state.lp_quote = data
    except Exception as e:
        logger.error(f"Error in {state_key}: {e}")

# 3. Thread Launcher with Context Bridge
if not st.session_state.threads_initialized:
    endpoints = [
        ('http://127.0.0.1:7070/priceengine', 'price_data'),
        ('http://127.0.0.1:7070/signal', 'signal'),
        ('http://127.0.0.1:7070/lpquote', 'lp')
    ]
    
    for url, key in endpoints:
        t = threading.Thread(target=sse_worker, args=(url, key), daemon=True)
        # --- CRITICAL STEP ---
        # This attaches the thread to the current browser session context
        add_script_run_ctx(t) 
        # ---------------------
        t.start()
    
    st.session_state.threads_initialized = True
    logger.info("Threads tethered to ScriptRunContext successfully.")

# 4. UI Layout
st.title("🛡️ Alpha Pricing Monitor")

# Display LP Info
if st.session_state.lp_quote:
    lp = st.session_state.lp_quote
    st.write(f"**LP Feed:** {lp.get('lpId')} | **Ref:** {lp.get('refPrice')}")

# Display Chart
if not st.session_state.price_history.empty:
    st.line_chart(st.session_state.price_history[['bid', 'ask', 'mid']])

st.metric("Signal", f"{st.session_state.signal_val:.4f}")

time.sleep(1)
st.rerun()
