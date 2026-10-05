package com.jph;

import javax.microedition.rms.RecordStore;
import net.rim.device.api.system.DeviceInfo;

/**
 * Persistent storage using RMS RecordStore.
 * Stores: server URL, device secret.
 */
public class Credentials {

    private static final String STORE_NAME = "JPHCredentials";
    private static final byte KEY_SERVER = 's';
    private static final byte KEY_SECRET = 'c';
    private static final byte KEY_CURSOR = 'n'; // sync cursor
    private static final byte KEY_DEFAULT_RECIPIENT = 'r'; // default recipient

    /**
     * Get the device ID. Uses the hardware PIN (8 hex chars).
     */
    public static String loadDeviceId() {
        int pin = DeviceInfo.getDeviceId();
        String s = Integer.toHexString(pin).toUpperCase();
        while (s.length() < 8) {
            s = "0" + s;
        }
        return s;
    }

    public static String loadDeviceSecret() {
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE_NAME, false);
            int count = rs.getNumRecords();
            for (int i = 1; i <= count; i++) {
                byte[] rec = rs.getRecord(i);
                if (rec.length > 0 && rec[0] == KEY_SECRET) {
                    String val = new String(rec, 1, rec.length - 1, "UTF-8");
                    rs.closeRecordStore();
                    return val;
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
            // No store or read error
        }
        return "";
    }

    public static void saveDeviceSecret(String secret) {
        saveValue(KEY_SECRET, secret);
    }

    public static String loadServer() {
        String s = loadValueSafe(KEY_SERVER);
        if (s.length() > 0) {
            return s;
        }
        return "http://192.168.188.110:8080";
    }

    public static void saveServer(String server) {
        saveValue(KEY_SERVER, server);
    }

    public static int loadSyncCursor() {
        String s = loadValueSafe(KEY_CURSOR);
        if (s.length() > 0) {
            try {
                return Integer.parseInt(s);
            } catch (Exception e) {
            }
        }
        return 0;
    }

    public static void saveSyncCursor(int cursor) {
        saveValue(KEY_CURSOR, Integer.toString(cursor));
    }

    public static String loadDefaultRecipient() {
        String s = loadValueSafe(KEY_DEFAULT_RECIPIENT);
        if (s.length() > 0) return s;
        return "agent-zero"; // Default to Agent Zero
    }

    public static void saveDefaultRecipient(String recipient) {
        saveValue(KEY_DEFAULT_RECIPIENT, recipient);
    }

    public static void reset() {
        try {
            RecordStore.deleteRecordStore(STORE_NAME);
        } catch (Exception e) {
            // Ignore if doesn't exist
        }
    }

    private static String loadValueSafe(byte key) {
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE_NAME, false);
            int count = rs.getNumRecords();
            for (int i = 1; i <= count; i++) {
                byte[] rec = rs.getRecord(i);
                if (rec.length > 0 && rec[0] == key) {
                    String val = new String(rec, 1, rec.length - 1, "UTF-8");
                    rs.closeRecordStore();
                    return val;
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
            // Ignore
        }
        return "";
    }

    private static void saveValue(byte key, String value) {
        if (value == null) {
            value = "";
        }
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE_NAME, true);
            byte[] valBytes = value.getBytes("UTF-8");
            byte[] data = new byte[1 + valBytes.length];
            data[0] = key;
            System.arraycopy(valBytes, 0, data, 1, valBytes.length);

            int count = rs.getNumRecords();
            boolean updated = false;
            for (int i = 1; i <= count; i++) {
                byte[] rec = rs.getRecord(i);
                if (rec.length > 0 && rec[0] == key) {
                    rs.setRecord(i, data, 0, data.length);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                rs.addRecord(data, 0, data.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {
            // Fail silently
        }
    }
}
