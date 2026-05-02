# Use a specific, stable Python version
FROM python:3.12-slim

# Set working directory inside the container
WORKDIR /app

# Install system dependencies if any (e.g., for certain plotting backends)
RUN apt-get update && apt-get install -y \
    build-essential \
    curl \
    software-properties-common \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first to leverage Docker cache
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy the rest of the frontend source code
COPY . .

# Streamlit uses port 8501 by default
EXPOSE 8501

# Healthcheck to ensure the UI is responsive
HEALTHCHECK CMD curl --fail http://localhost:8501/_stcore/health

# Run the application
# address 0.0.0.0 is required to allow external access from the host/docker network
CMD ["streamlit", "run", "app.py", "--server.port=8501", "--server.address=0.0.0.0"]
