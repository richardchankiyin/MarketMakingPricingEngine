# Use the standard slim image
FROM python:3.12-slim

WORKDIR /app

# Simplified update: only curl for the healthcheck
# --fix-missing handles transient network issues during build
RUN apt-get update --fix-missing && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Standard Python setup
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# to point to backend service
ENV BACKEND_ADDR="mm-gateway-backend:7070"

EXPOSE 8501

HEALTHCHECK CMD curl --fail http://localhost:8501/_stcore/health || exit 1

CMD ["streamlit", "run", "app.py", "--server.port=8501", "--server.address=0.0.0.0"]
