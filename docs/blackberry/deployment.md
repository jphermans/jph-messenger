# BlackBerry Deployment Guide

## Physical Device Setup

### Prerequisites

1. **BlackBerry Bold 9790 (RE071UW)** with OS 7.1
2. **Windows 7 32-bit VM** with:
   - JDK 6 (32-bit, update 14+)
   - BlackBerry Java SDK 7.1
   - BlackBerry Plug-in for Eclipse 2.0.0 (optional)
3. **Wi-Fi network** for the BlackBerry

### SDK Setup (Historical - Verify on Archive.org)

```
1. Download BlackBerry Java SDK 7.1 from archive.org
2. Install on Windows 7 32-bit VM
3. Verify JDK 6 path in BB_JDK environment variable
4. Test: rapc.exe -version
```

### Building the Application

```bash
# Compile Java source
javac -source 1.3 -target 1.3 -bootclasspath ${BB_SDK}/lib/net_rim_api.jar \
  -d build src/**/*.java

# Package with RAPC
rapc.exe -codename=MyApp -output=MyApp.cod \
  -midlet=True -icon=icon.png build/*.class

# Generate JAD
# (RAPC generates .jad automatically)
```

### Deployment Methods

#### 1. Over-The-Air (OTA) via HTTP (Preferred)

Host files on a web server accessible from the BlackBerry:

```
http://your-gateway/ota/MyApp.jad
http://your-gateway/ota/MyApp-1.cod
```

On BlackBerry Browser, visit the JAD URL.

#### 2. JavaLoader via USB

```bash
# Connect BlackBerry via USB
javaloader.exe load -u MyApp.cod

# View device logs
javaloader.exe eventlog

# Erase application
javaloader.exe erase -u MyApp
```

### First Connection Test

After deploying the JPH Messenger app:

1. Connect BlackBerry to Wi-Fi
2. Launch the app
3. App should call `/legacy/v1/register`
4. Store the returned `device_secret` securely
5. App should call `/legacy/v1/ping`

Expected response:
```json
{
  "status": "ok",
  "server": "JPH Messenger",
  "version": "0.1.0-poc",
  "timestamp": "2026-..."
}
```

### Troubleshooting

| Issue | Solution |
|---|---|
| Connection refused | Check Wi-Fi, gateway URL, device credentials |
| TLS error | BB OS 7.1 only supports TLS 1.0 - ensure gateway accepts legacy TLS |
| App won't launch | Check COD file, verify signing (or run unsigned for dev) |
| JavaLoader not found | SDK not in PATH, or running on non-Windows |
