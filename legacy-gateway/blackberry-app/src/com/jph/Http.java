package com.jph;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

/**
 * HTTP client using Connector.open with Wi-Fi interface.
 * Priority: Wi-Fi > Cellular (direct TCP), no BIS/BES.
 */
public class Http {

    /**
     * Perform an HTTP request.
     *
     * @param method   GET or POST
     * @param url      Full URL
     * @param body     Request body (null for GET)
     * @param deviceId Device ID header (null for register)
     * @param secret   Device secret header (null for register)
     * @return String[2] = {statusCode, responseBody}
     */
    public static String[] request(String method, String url, String body,
            String deviceId, String secret) throws Exception {

        HttpConnection conn = null;
        InputStream in = null;
        try {
            conn = openConnection(url);
            conn.setRequestMethod(method);
            // Detect content type from body: JSON if starts with {, else form-encoded
            if (body != null && body.length() > 0 && body.charAt(0) == '{') {
                conn.setRequestProperty("Content-Type", "application/json");
            } else {
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            }
            conn.setRequestProperty("Accept", "application/json");

            // Add device credentials if provided
            if (deviceId != null && deviceId.length() > 0) {
                conn.setRequestProperty("X-Device-ID", deviceId);
            }
            if (secret != null && secret.length() > 0) {
                conn.setRequestProperty("X-Device-Secret", secret);
            }

            // Write body for POST (form-encoded)
            if (body != null && body.length() > 0) {
                java.io.DataOutputStream dos = conn.openDataOutputStream();
                dos.write(body.getBytes("UTF-8"));
                dos.close();
            }

            // Read response
            int status = conn.getResponseCode();
            String statusStr = Integer.toString(status);

            if (status >= 200 && status < 400) {
                in = conn.openInputStream();
                String response = readString(in);
                return new String[] { statusStr, response };
            } else {
                // Read error body
                try {
                    in = conn.openInputStream();
                    String error = readString(in);
                    return new String[] { statusStr, error };
                } catch (Exception e) {
                    return new String[] { statusStr, "{error:'" + conn.getResponseMessage() + "'}" };
                }
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception e) { }
            if (conn != null) try { conn.close(); } catch (Exception e) { }
        }
    }

    private static HttpConnection openConnection(String url) throws Exception {
        // BB OS 7 Wi-Fi connection: use ;interface=wifi ONLY
        // Do NOT add ;deviceside=true with Wi-Fi - that's contradictory
        // and causes "APN is not specified" error
        String wifiUrl = url;
        if (url.indexOf(";interface") == -1) {
            wifiUrl = url + ";interface=wifi";
        }
        
        try {
            return (HttpConnection) Connector.open(wifiUrl);
        } catch (Exception e) {
            // Fallback: try without interface suffix (let BB choose transport)
            return (HttpConnection) Connector.open(url);
        }
    }

    private static String readString(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return new String(baos.toByteArray(), "UTF-8");
    }
}
