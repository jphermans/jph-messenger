# JPH Legacy Gateway - lightweight image
# Base: python:3.13-slim (Debian-based, ~150MB; avoids Alpine musl issues)
FROM python:3.13-slim

# Do not write .pyc / enable unbuffered logs
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Install dependencies first (cached layer)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY main.py .

# Copy BlackBerry OTA install files
COPY ota ./ota

# Run as non-root user
RUN useradd --create-home --uid 1000 jph && chown -R jph:jph /app
USER jph

EXPOSE 8080

# Healthcheck without curl (not in slim by default)
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=4).status==200 else 1)"]

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
