"""
JPH Legacy Gateway - Phase 6 PoC

Minimal gateway implementing:
- Device registration/authentication
- /legacy/v1/ping endpoint
- Legacy API v1
"""

import os
import secrets
import hashlib
import httpx
from datetime import datetime, timedelta
from typing import Optional

from fastapi import FastAPI, HTTPException, Header, Depends, Form
from fastapi.responses import JSONResponse, HTMLResponse, FileResponse
from pydantic import BaseModel
import structlog
import os

# Configuration
LOG = structlog.get_logger()

# Agent Zero configuration (from environment)
A0_API_URL = os.getenv("A0_API_URL", "http://host.docker.internal:5000")
A0_API_KEY = os.getenv("A0_API_KEY", "")

# Configuration
LOG = structlog.get_logger()

# In-memory storage for PoC (replace with DB in production)
devices_db: dict[str, dict] = {}
messages_db: list[dict] = []  # Message queue
next_message_id = 1

# Agent Zero session context (device_id -> conversation context)
a0_sessions: dict[str, dict] = {}

app = FastAPI(title="JPH Legacy Gateway")

# Serve OTA BlackBerry app files at /ota/ with exact RIM MIME types.
# BB OS browsers are strict: .jad must be text/vnd.sun.j2me.app-descriptor
# (no charset suffix) and .cod must be application/vnd.rim.cod, or the
# browser offers to save instead of installing.

OTA_FILES = {
    "JPHMessenger.jad": "text/vnd.sun.j2me.app-descriptor",
    "JPHMessenger.cod": "application/vnd.rim.cod",
    "JPHMessenger.jar": "application/java-archive",
}


@app.get("/ota/{filename}", tags=["ota"])
async def ota_file(filename: str):
    if filename not in OTA_FILES:
        raise HTTPException(status_code=404, detail="Unknown OTA file")
    path = os.path.join("ota", filename)
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(
        path,
        media_type=OTA_FILES[filename],
        filename=filename,
        headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0"},
    )


# =============================================================================
# PWA - Modern phone web app
# =============================================================================

@app.get("/app/", tags=["pwa"])
async def pwa_index():
    """Serve the PWA chat app."""
    path = os.path.join("pwa", "index.html")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="PWA not found")
    return FileResponse(path, media_type="text/html")


@app.get("/pwa/manifest.json", tags=["pwa"])
async def pwa_manifest():
    """Serve PWA manifest."""
    path = os.path.join("pwa", "manifest.json")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Manifest not found")
    return FileResponse(path, media_type="application/json")


@app.get("/pwa/sw.js", tags=["pwa"])
async def pwa_service_worker():
    """Serve PWA service worker (must be same-origin scope)."""
    path = os.path.join("pwa", "sw.js")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Service worker not found")
    return FileResponse(
        path,
        media_type="application/javascript",
        headers={"Service-Worker-Allowed": "/"},
    )


@app.get("/pwa/icon-{size}.png", tags=["pwa"])
async def pwa_icon(size: int):
    """Serve PWA icon."""
    path = os.path.join("pwa", f"icon-{size}.png")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Icon not found")
    return FileResponse(path, media_type="image/png")


# =============================================================================
# Root endpoint - friendly HTML for browser visitors
# =============================================================================

@app.get("/", tags=["root"])
async def root():
    """Human-friendly root page."""
    html = """<!DOCTYPE html>
<html><head><title>JPH Messenger Gateway</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;max-width:600px;margin:40px auto;padding:20px;line-height:1.6}
h1{color:#333}code{background:#f4f4f4;padding:2px 6px;border-radius:3px}
.endpoints{margin-top:20px}
.endpoint{background:#f9f9f9;padding:10px;margin:10px 0;border-left:3px solid #007bff}
</style></head>
<body>
<h1>📱 JPH Messenger Gateway</h1>
<p><strong>Status:</strong> Running ✅</p>
<p>This is the JPH Legacy Gateway for BlackBerry Bold 9790.</p>
<div class="endpoints">
<div class="endpoint"><code>GET /health</code> - Health check</div>
<div class="endpoint"><code>POST /legacy/v1/register</code> - Register device</div>
<div class="endpoint"><code>GET /legacy/v1/ping</code> - Ping (requires auth)</div>
<div class="endpoint"><code>POST /legacy/v1/messages</code> - Send message</div>
<div class="endpoint"><code>GET /legacy/v1/messages</code> - Sync messages</div>
</div>
<p><em>Use a REST client or BlackBerry app to interact.</em></p>
</body></html>"""
    return HTMLResponse(content=html)


# =============================================================================
# Models
# =============================================================================

class DeviceRegisterRequest(BaseModel):
    """Register a new BlackBerry device."""
    device_id: str  # e.g., JPH ID like 8A42F91C
    public_key: Optional[str] = None


class DeviceRegisterResponse(BaseModel):
    """Response after device registration."""
    device_id: str
    device_secret: str  # Store securely on device!
    status: str


class PingResponse(BaseModel):
    """Ping response."""
    status: str
    server: str
    version: str
    timestamp: str


class ErrorResponse(BaseModel):
    """Error response."""
    error: str
    code: int


class MessageSendRequest(BaseModel):
    """Send a message from the device."""
    recipient: str  # JPH ID, contact name, or "agent-zero"
    body: str
    idempotency_key: Optional[str] = None  # Prevent duplicates on retry


# Alternative: simple form fields for BlackBerry (no JSON encoding needed)
class RawMessageRequest(BaseModel):
    """Simple raw message format - special characters preserved."""
    to: str
    msg: str
    idem: Optional[str] = None


class MessageSendResponse(BaseModel):
    """Acknowledge a stored message."""
    message_id: int
    status: str  # queued | delivered
    recipient: str


class MessageItem(BaseModel):
    """A message returned to the device."""
    message_id: int
    sender: str
    recipient: str
    body: str
    timestamp: str
    truncated: bool = False


class SyncResponse(BaseModel):
    """Cursor-based incremental sync response."""
    messages: list[MessageItem]
    next_cursor: int
    has_more: bool


# =============================================================================
# Utilities
# =============================================================================

def generate_device_secret() -> str:
    """Generate a secure device secret."""
    return secrets.token_hex(32)


def hash_secret(secret: str) -> str:
    """Hash a device secret for storage."""
    return hashlib.sha256(secret.encode()).hexdigest()


async def verify_device(
    x_device_id: Optional[str] = Header(None),
    x_device_secret: Optional[str] = Header(None)
) -> dict:
    """Verify device credentials."""
    if not x_device_id or not x_device_secret:
        raise HTTPException(
            status_code=401,
            detail={"error": "Missing device credentials", "code": 1}
        )
    
    device = devices_db.get(x_device_id)
    if not device:
        raise HTTPException(
            status_code=401,
            detail={"error": "Unknown device", "code": 2}
        )
    
    if device["secret_hash"] != hash_secret(x_device_secret):
        raise HTTPException(
            status_code=401,
            detail={"error": "Invalid device secret", "code": 3}
        )
    
    return device


async def dispatch_to_agent_zero(message: dict) -> None:
    """
    Forward a message to Agent Zero and queue the reply.
    
    Uses A0 /api/message endpoint:
    - POST /api/message with {message, context_id}
    - Auth: X-API-Key header with mcp_server_token
    - Response: {context_id, response}
    """
    device_id = message["sender"]
    session = a0_sessions.get(device_id, {})
    context_id = session.get("context_id")
    
    LOG.info("a0.dispatch", device_id=device_id, context_id=context_id)
    
    try:
        async with httpx.AsyncClient(timeout=120.0) as client:
            payload = {
                "message": message["body"],
            }
            # Include context_id if we have an existing conversation
            if context_id:
                payload["context_id"] = context_id
            
            headers = {"Content-Type": "application/json"}
            if A0_API_KEY:
                headers["X-API-Key"] = A0_API_KEY
            
            resp = await client.post(
                f"{A0_API_URL}/api/message",
                json=payload,
                headers=headers
            )
            resp.raise_for_status()
            
            data = resp.json()
            
            # Check for error responses
            if "error" in data:
                LOG.error("a0.api_error", error=data.get("error"))
                response_text = f"[Agent Zero error: {data.get('error')}]"
            else:
                # Persist context for multi-turn conversations
                new_context = data.get("context_id")
                if new_context:
                    a0_sessions[device_id] = {"context_id": new_context}
                
                # Extract reply text
                response_text = data.get("response") or ""
                if not response_text:
                    response_text = "[A0 returned empty response]"
            
    except httpx.TimeoutException:
        response_text = "[Agent Zero timeout — try again later]"
    except httpx.HTTPStatusError as exc:
        LOG.error("a0.http_error", status=exc.response.status_code, body=exc.response.text[:200])
        response_text = f"[Agent Zero HTTP {exc.response.status_code}]"
    except httpx.HTTPError as exc:
        LOG.error("a0.http_error", error=str(exc))
        response_text = "[Agent Zero unavailable]"
    except Exception as exc:
        LOG.error("a0.error", error=str(exc))
        response_text = "[Agent Zero error]"
    
    # Truncate for low-bandwidth delivery (full text stays server-side)
    global next_message_id
    truncated_body = response_text[:500]
    was_truncated = len(response_text) > 500
    
    reply = {
        "id": next_message_id,
        "idempotency_key": None,
    }
    reply.update({
        "device_id": device_id,
        "sender": "agent-zero",
        "recipient": device_id,
        "body": truncated_body if was_truncated else response_text,
        "truncated": was_truncated,
        "timestamp": datetime.utcnow().isoformat(),
        "status": "delivered",
    })
    messages_db.append(reply)
    next_message_id += 1


# =============================================================================
# Health & Info
# =============================================================================

@app.get("/health")
async def health():
    """Health check endpoint."""
    return {"status": "healthy", "timestamp": datetime.utcnow().isoformat()}


# =============================================================================
# Legacy API v1
# =============================================================================

@app.post("/legacy/v1/register", response_model=DeviceRegisterResponse)
async def register_device(request: DeviceRegisterRequest):
    """
    Register a new BlackBerry device.
    
    The device_id is a public identifier (like a JPH ID).
    The device_secret must be stored securely on the device.
    """
    LOG.info("device.register", device_id=request.device_id)
    
    if request.device_id in devices_db:
        # Allow re-registration: rotate the secret so a reinstalled app
        # (or lost RMS store) can recover instead of being locked out by 409.
        device_secret = generate_device_secret()
        devices_db[request.device_id] = {
            "device_id": request.device_id,
            "secret_hash": hash_secret(device_secret),
            "public_key": request.public_key,
            "registered_at": devices_db[request.device_id].get("registered_at"),
            "last_seen": datetime.utcnow().isoformat(),
        }
        LOG.info("device.reregistered", device_id=request.device_id)
        return DeviceRegisterResponse(
            device_id=request.device_id,
            device_secret=device_secret,
            status="reregistered"
        )
    
    device_secret = generate_device_secret()
    
    devices_db[request.device_id] = {
        "device_id": request.device_id,
        "secret_hash": hash_secret(device_secret),
        "public_key": request.public_key,
        "registered_at": datetime.utcnow().isoformat(),
        "last_seen": None,
    }
    
    return DeviceRegisterResponse(
        device_id=request.device_id,
        device_secret=device_secret,
        status="registered"
    )


@app.delete("/legacy/v1/unregister")
async def unregister_device(device: dict = Depends(verify_device)):
    """
    Unregister this device.

    Requires valid X-Device-ID and X-Device-Secret headers.
    Removes the device, its messages, and its Agent Zero session.
    The device can register again afterwards with a fresh secret.
    """
    device_id = device["device_id"]
    devices_db.pop(device_id, None)
    a0_sessions.pop(device_id, None)

    # Remove messages to/from this device
    global messages_db
    messages_db = [m for m in messages_db
                   if m.get("sender") != device_id and m.get("recipient") != device_id]

    LOG.info("device.unregister", device_id=device_id)
    return {"device_id": device_id, "status": "unregistered"}


@app.get("/legacy/v1/ping", response_model=PingResponse)
async def legacy_ping(device: dict = Depends(verify_device)):
    """
    Legacy ping endpoint.
    
    Returns server status - the first PoC test.
    
    Headers required:
    - X-Device-ID: The device identifier
    - X-Device-Secret: The device secret
    """
    # Update last seen
    device["last_seen"] = datetime.utcnow().isoformat()
    
    LOG.info("legacy.ping", device_id=device["device_id"])
    
    return PingResponse(
        status="ok",
        server="JPH Messenger",
        version="0.1.0-poc",
        timestamp=datetime.utcnow().isoformat()
    )


@app.get("/legacy/v1/status")
async def legacy_status(device: dict = Depends(verify_device)):
    """Get device status."""
    return {
        "device_id": device["device_id"],
        "registered_at": device["registered_at"],
        "last_seen": device["last_seen"],
    }


@app.post("/legacy/v1/messages", response_model=MessageSendResponse)
async def send_message(
    request: MessageSendRequest,
    device: dict = Depends(verify_device)
):
    """Send a message (JSON format)."""
    return await _store_message(
        recipient=request.recipient,
        body=request.body,
        idempotency_key=request.idempotency_key,
        device=device
    )


@app.post("/legacy/v1/msg", response_model=MessageSendResponse)
async def send_message_form(
    to: str = Form(...),
    msg: str = Form(...),
    idem: str = Form(None),
    device: dict = Depends(verify_device)
):
    """
    Simple form endpoint for BlackBerry.
    Special characters are preserved - no JSON encoding needed.
    """
    return await _store_message(
        recipient=to,
        body=msg,
        idempotency_key=idem,
        device=device
    )


async def _store_message(recipient: str, body: str, idempotency_key: str, device: dict):
    """Shared message storage logic."""
    global next_message_id
    
    # Check for duplicate (idempotency)
    for msg in messages_db:
        if msg.get("idempotency_key") == idempotency_key:
            LOG.info("message.duplicate", key=idempotency_key)
            return MessageSendResponse(
                message_id=msg["id"],
                status="queued",
                recipient=recipient
            )
    
    message = {
        "id": next_message_id,
        "idempotency_key": idempotency_key,
        "device_id": device["device_id"],
        "sender": device["device_id"],
        "recipient": recipient,
        "body": body,
        "timestamp": datetime.utcnow().isoformat(),
        "status": "queued",  # queued | sent | delivered | failed
    }
    
    messages_db.append(message)
    next_message_id += 1
    
    # Route to Agent Zero if recipient is "agent-zero"
    if recipient.lower() == "agent-zero":
        await dispatch_to_agent_zero(message)
    
    LOG.info("message.send", 
              device_id=device["device_id"],
              recipient=recipient,
              message_id=message["id"])
    
    return MessageSendResponse(
        message_id=message["id"],
        status="queued",
        recipient=recipient
    )


@app.get("/legacy/v1/messages", response_model=SyncResponse)
async def get_messages(
    cursor: int = 0,
    limit: int = 50,
    device: dict = Depends(verify_device)
):
    """
    Cursor-based incremental sync.
    
    - cursor: last message_id the client received (0 for initial sync)
    - limit: max messages to return
    
    Returns messages after cursor, plus next_cursor for the next call.
    """
    device_id = device["device_id"]
    
    # Get messages sent TO this device (inbound) or FROM this device (outbound status)
    # For PoC, return all messages involving this device
    relevant = [
        m for m in messages_db
        if m.get("id") > cursor and (
            m.get("recipient") == device_id or 
            m.get("sender") == device_id
        )
    ][:limit]
    
    next_cursor = max([m["id"] for m in relevant], default=cursor)
    
    # Convert to response format (with truncation for long bodies)
    items = []
    for m in relevant:
        body = m["body"]
        truncated = len(body) > 500
        if truncated:
            body = body[:500] + "..."
        
        items.append(MessageItem(
            message_id=m["id"],
            sender=m["sender"],
            recipient=m["recipient"],
            body=body,
            timestamp=m["timestamp"],
            truncated=truncated
        ))
    
    return SyncResponse(
        messages=items,
        next_cursor=next_cursor,
        has_more=len(relevant) == limit
    )


# =============================================================================
# Main
# =============================================================================

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
