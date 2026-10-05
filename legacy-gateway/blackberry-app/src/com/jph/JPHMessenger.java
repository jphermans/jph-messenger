package com.jph;

import net.rim.device.api.system.DeviceInfo;
import net.rim.device.api.system.EventInjector;
import net.rim.device.api.ui.MenuItem;
import net.rim.device.api.ui.UiApplication;
import net.rim.device.api.ui.component.Dialog;
import net.rim.device.api.ui.component.EditField;
import net.rim.device.api.ui.component.LabelField;
import net.rim.device.api.ui.component.RichTextField;
import net.rim.device.api.ui.container.MainScreen;

/**
 * JPH Messenger - Phase 6 Network PoC for BlackBerry Bold 9790 (OS 7.1).
 */
public class JPHMessenger extends UiApplication {

    public static void main(String[] args) {
        new JPHMessenger().enterEventDispatcher();
    }

    // App version - bump for each release
    private static final String APP_VERSION = "0.3.2";

    public JPHMessenger() {
        pushScreen(new MainAppScreen());
    }

    public static class MainAppScreen extends MainScreen {

        private EditField serverField;
        private RichTextField statusField;
        private LabelField deviceLabel;
        private EditField recipientField;
        private EditField messageField;
        private int syncCursor = 0;

        private MenuItem registerItem = new MenuItem("Register", 100, 10) {
            public void run() {
                saveServer();
                doRegister();
            }
        };

        private MenuItem pingItem = new MenuItem("Ping", 110, 20) {
            public void run() {
                saveServer();
                doPing();
            }
        };

        private MenuItem resetItem = new MenuItem("Reset credentials", 120, 30) {
            public void run() {
                Credentials.reset();
                updateDeviceLabel();
                setStatus("Credentials cleared. Use Register again.");
                offerReboot();
            }
        };

        private MenuItem unregisterItem = new MenuItem("Unregister", 122, 32) {
            public void run() {
                saveServer();
                runAsync("unregister");
            }
        };

        private MenuItem sendItem = new MenuItem("Send message", 130, 40) {
            public void run() {
                runAsync("send");
            }
        };

        private MenuItem settingsItem = new MenuItem("Settings", 125, 35) {
            public void run() {
                showSettingsDialog();
            }
        };

        private MenuItem syncItem = new MenuItem("Sync messages", 140, 50) {
            public void run() {
                runAsync("sync");
            }
        };

        public MainAppScreen() {
            setTitle("JPH Messenger v" + APP_VERSION);

            serverField = new EditField("Server: ", Credentials.loadServer(), 200,
                    EditField.EDITABLE);
            add(serverField);
            add(new LabelField(""));

            // Recipient field (default saved in settings)
            recipientField = new EditField("To: ", Credentials.loadDefaultRecipient(), 100, EditField.EDITABLE);
            add(recipientField);

            // Message body field
            messageField = new EditField("Msg: ", "", 200, EditField.EDITABLE);
            add(messageField);
            add(new LabelField(""));

            deviceLabel = new LabelField("");
            add(deviceLabel);
            updateDeviceLabel();
            syncCursor = Credentials.loadSyncCursor();

            statusField = new RichTextField("Ready. Menu: Register, Send, Sync.");
            add(statusField);
        }

        protected void makeMenu(net.rim.device.api.ui.component.Menu menu, int instance) {
            menu.add(settingsItem);
            menu.add(sendItem);
            menu.add(syncItem);
            menu.add(registerItem);
            menu.add(pingItem);
            menu.add(unregisterItem);
            menu.add(resetItem);
            super.makeMenu(menu, instance);
        }

        private void saveServer() {
            Credentials.saveServer(serverField.getText().trim());
        }

        private String baseUrl() {
            String url = serverField.getText().trim();
            if (url.length() > 0 && url.charAt(url.length() - 1) == '/') {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }

        private void updateDeviceLabel() {
            String id = Credentials.loadDeviceId();
            String secret = Credentials.loadDeviceSecret();
            if (secret.length() > 0) {
                deviceLabel.setText("Device: " + id + " (registered)");
            } else {
                deviceLabel.setText("Device: " + id + " (not registered)");
            }
        }

        private void setStatus(final String text) {
            UiApplication.getUiApplication().invokeLater(new Runnable() {
                public void run() {
                    statusField.setText(text);
                }
            });
        }

        private void runAsync(final String action) {
            setStatus(action + " ... working (background thread)");
            Thread t = new Thread() {
                public void run() {
                    try {
                        if ("register".equals(action)) {
                            performRegister();
                        } else if ("ping".equals(action)) {
                            performPing();
                        } else if ("send".equals(action)) {
                            performSend();
                        } else if ("sync".equals(action)) {
                            performSync();
                        } else if ("unregister".equals(action)) {
                            performUnregister();
                        }
                    } catch (final Throwable e) {
                        setStatus(action + " FAILED: " + e);
                    }
                }
            };
            t.start();
        }

        private void doRegister() {
            runAsync("register");
        }

        private void doPing() {
            String secret = Credentials.loadDeviceSecret();
            if (secret.length() == 0) {
                Dialog.alert("Not registered yet. Run Register first.");
                return;
            }
            runAsync("ping");
        }

        private void performRegister() throws Exception {
            String deviceId = Credentials.loadDeviceId();
            String body = "{\"device_id\":\"" + deviceId + "\"}";
            String[] resp = Http.request("POST", baseUrl() + "/legacy/v1/register",
                    body, null, null);
            String secret = Json.getString(resp[1], "device_secret");
            if (secret != null && secret.length() > 0) {
                Credentials.saveDeviceSecret(secret);
                UiApplication.getUiApplication().invokeLater(new Runnable() {
                    public void run() {
                        updateDeviceLabel();
                    }
                });
                setStatus("Registered OK (HTTP " + resp[0] + "). Now run Ping.");
            } else {
                setStatus("Register failed (HTTP " + resp[0] + "): " + resp[1]);
            }
        }

        private String urlEncode(String s) {
            if (s == null) return "";
            StringBuffer sb = new StringBuffer();
            byte[] bytes;
            try { bytes = s.getBytes("UTF-8"); } catch (Exception e) { bytes = s.getBytes(); }
            for (int i = 0; i < bytes.length; i++) {
                char c = (char) (bytes[i] & 0xFF);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.' || c == '~') {
                    sb.append(c);
                } else {
                    // Percent-encode everything else (incl. apostrophe, quotes, spaces, UTF-8 bytes)
                    sb.append('%');
                    String hex = Integer.toHexString((int) c);
                    if (hex.length() == 1) sb.append('0');
                    sb.append(hex);
                }
            }
            return sb.toString();
        }

        private void showSettingsDialog() {
            // Show current settings - editing requires more complex dialog
            Dialog.alert("Server: " + Credentials.loadServer() + "\nDefault To: " + Credentials.loadDefaultRecipient() + "\nVersion: " + APP_VERSION);
        }

        private void performPing() throws Exception {
            String deviceId = Credentials.loadDeviceId();
            String secret = Credentials.loadDeviceSecret();
            if (secret.length() == 0) {
                setStatus("Not registered. Run Register first.");
                return;
            }
            String[] resp = Http.request("GET", baseUrl() + "/legacy/v1/ping", null, deviceId, secret);
            String status = Json.getString(resp[1], "status");
            if ("ok".equals(status)) {
                setStatus("PING OK (HTTP " + resp[0] + "): " + resp[1]);
            } else {
                setStatus("Ping failed (HTTP " + resp[0] + "): " + resp[1]);
            }
        }

        private void performSend() throws Exception {
            String deviceId = Credentials.loadDeviceId();
            String secret = Credentials.loadDeviceSecret();
            if (secret.length() == 0) {
                setStatus("Not registered. Run Register first.");
                return;
            }
            String recipient = recipientField.getText().trim();
            String body = messageField.getText().trim();
            if (recipient.length() == 0 || body.length() == 0) {
                setStatus("Fill in To: and Msg: fields first.");
                return;
            }
            String idem = deviceId + "-" + System.currentTimeMillis();
            // Form-encoded body: special characters preserved exactly (UTF-8 + URL encoding)
            String payload = "to=" + urlEncode(recipient) + "&msg=" + urlEncode(body) + "&idem=" + urlEncode(idem);
            String[] resp = Http.request("POST", baseUrl() + "/legacy/v1/msg", payload, deviceId, secret);
            if (resp[0].startsWith("2")) {
                final String finalRecipient = recipient;
                final String code = resp[0];
                UiApplication.getUiApplication().invokeLater(new Runnable() {
                    public void run() {
                        messageField.setText("");
                        statusField.setText("Sent to " + finalRecipient + " (HTTP " + code + "). Use Sync for replies.");
                    }
                });
            } else {
                setStatus("Send failed (HTTP " + resp[0] + "): " + resp[1]);
            }
        }

        private void performSync() throws Exception {
            String deviceId = Credentials.loadDeviceId();
            String secret = Credentials.loadDeviceSecret();
            if (secret.length() == 0) {
                setStatus("Not registered. Run Register first.");
                return;
            }
            final String[] resp = Http.request("GET",
                    baseUrl() + "/legacy/v1/messages?cursor=" + syncCursor,
                    null, deviceId, secret);
            if (!resp[0].startsWith("2")) {
                setStatus("Sync failed (HTTP " + resp[0] + "): " + resp[1]);
                return;
            }
            String json = resp[1];
            int newCursor = Json.getInt(json, "next_cursor", syncCursor);
            int count = 0;
            StringBuffer sb = new StringBuffer();
            // Split into individual message objects on "message_id"
            int pos = 0;
            String marker = "\"message_id\"";
            int idx;
            while ((idx = json.indexOf(marker, pos)) >= 0) {
                // Extract from this marker to the next one or end
                int nextIdx = json.indexOf(marker, idx + marker.length());
                int end = (nextIdx < 0) ? json.length() : nextIdx;
                String chunk = json.substring(idx, end);
                String sender = Json.getString(chunk, "sender");
                String msgBody = Json.getString(chunk, "body");
                if (msgBody != null) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append('[').append(sender).append("] ");
                    // Truncate long bodies for low-bandwidth display
                    if (msgBody.length() > 300) {
                        sb.append(msgBody.substring(0, 300)).append("...");
                    } else {
                        sb.append(msgBody);
                    }
                    count++;
                }
                pos = idx + marker.length();
            }
            final int received = count;
            final String display = sb.toString();
            final boolean updated = newCursor > syncCursor;
            syncCursor = newCursor;
            Credentials.saveSyncCursor(newCursor);
            UiApplication.getUiApplication().invokeLater(new Runnable() {
                public void run() {
                    if (received == 0) {
                        statusField.setText("No new messages.");
                    } else {
                        statusField.setText(received + " new message(s):\n" + display);
                    }
                }
            });
        }

        private void performUnregister() throws Exception {
            String deviceId = Credentials.loadDeviceId();
            String secret = Credentials.loadDeviceSecret();
            if (secret.length() == 0) {
                // Not registered - just clear locally
                Credentials.reset();
                updateDeviceLabel();
                setStatus("Not registered. Local credentials cleared.");
                return;
            }
            // Call DELETE /legacy/v1/unregister
            String[] resp = Http.request("DELETE", baseUrl() + "/legacy/v1/unregister", 
                    null, deviceId, secret);
            if (resp[0].startsWith("2") || resp[0].equals("404")) {
                // Success or already gone - clear local credentials
                Credentials.reset();
                final String status = resp[0].startsWith("2") ? 
                        "Unregistered from server." : "Not found on server.";
                UiApplication.getUiApplication().invokeLater(new Runnable() {
                    public void run() {
                        updateDeviceLabel();
                        statusField.setText(status + " Local credentials cleared.");
                        offerReboot();
                    }
                });
            } else {
                setStatus("Unregister failed (HTTP " + resp[0] + "): " + resp[1]);
            }
        }

        /**
         * Ask the user and force a soft reset by injecting the
         * Alt + Right Shift + Del key sequence (verified BB OS 7 API:
         * EventInjector.KeyCodeEvent + invokeEvent, Keypad constants).
         */
        private void offerReboot() {
            if (Dialog.ask(Dialog.D_YES_NO, "Reboot device now to complete removal?") == Dialog.YES) {
                forceReboot();
            }
        }

        private void forceReboot() {
            try {
                // Alt down
                EventInjector.invokeEvent(new EventInjector.KeyCodeEvent(
                        EventInjector.KeyEvent.KEY_DOWN, (char) 257, 100));
                // Right Shift down
                EventInjector.invokeEvent(new EventInjector.KeyCodeEvent(
                        EventInjector.KeyEvent.KEY_DOWN, (char) 256, 150));
                // Del down + up (triggers soft reset)
                EventInjector.invokeEvent(new EventInjector.KeyCodeEvent(
                        EventInjector.KeyEvent.KEY_DOWN, (char) 127, 200));
                EventInjector.invokeEvent(new EventInjector.KeyCodeEvent(
                        EventInjector.KeyEvent.KEY_UP, (char) 127, 250));
            } catch (Throwable t) {
                // Fallback: manual instructions if injection is blocked
                Dialog.alert("Reboot manually:\nAlt + Right Shift + Del");
            }
        }
    }
}
