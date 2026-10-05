package com.jph;

/**
 * Minimal JSON parser for the BlackBerry PoC.
 * Only handles: getString(json, key) where value is a quoted string.
 */
public class Json {

    /**
     * Extract a string value from a JSON object.
     *
     * @param json  JSON string, e.g. {"status":"ok","version":"1.0"}
     * @param key   Key to find, e.g. "status"
     * @return The string value, or null if not found
     */
    public static String getString(String json, String key) {
        if (json == null || key == null) return "";

        // Find the key - search for "key":
        String search = "\"" + key + "\"";
        int keyPos = json.indexOf(search);
        if (keyPos < 0) return "";

        // Move past the key and find colon
        int pos = keyPos + search.length();
        while (pos < json.length() && json.charAt(pos) != ':') {
            pos++;
        }
        if (pos >= json.length()) return "";
        pos++; // skip colon

        // Skip whitespace
        while (pos < json.length() && json.charAt(pos) <= ' ') pos++;
        if (pos >= json.length()) return "";

        // Handle different value types
        char c = json.charAt(pos);
        
        // If it's a quoted string, parse it
        if (c == '"') {
            pos++; // skip opening quote
            StringBuffer sb = new StringBuffer();
            while (pos < json.length()) {
                char ch = json.charAt(pos);
                if (ch == '\\' && pos + 1 < json.length()) {
                    pos++;
                    char escaped = json.charAt(pos);
                    if (escaped == 'n') sb.append('\n');
                    else if (escaped == 'r') sb.append('\r');
                    else if (escaped == 't') sb.append('\t');
                    else if (escaped == '\\') sb.append('\\');
                    else if (escaped == '"') sb.append('"');
                    else sb.append(escaped);
                } else if (ch == '"') {
                    break; // closing quote
                } else {
                    sb.append(ch);
                }
                pos++;
            }
            return sb.toString();
        }
        
        // If it's null, return empty
        if (c == 'n') {
            return "";
        }
        
        // If it's a number, read until non-digit
        if (c == '-' || Character.isDigit(c)) {
            StringBuffer sb = new StringBuffer();
            while (pos < json.length()) {
                char ch = json.charAt(pos);
                if (Character.isDigit(ch) || ch == '-' || ch == '.') {
                    sb.append(ch);
                    pos++;
                } else {
                    break;
                }
            }
            return sb.toString();
        }
        
        // If it's true/false
        if (json.substring(pos).startsWith("true")) return "true";
        if (json.substring(pos).startsWith("false")) return "false";
        
        return "";
    }

    /**
     * Extract an integer value from a JSON object.
     *
     * @param json JSON string
     * @param key  Key to find
     * @param def  Default if not found
     * @return The int value, or def
     */
    public static int getInt(String json, String key, int def) {
        if (json == null || key == null) return def;
        String search = "\"" + key + "\"";
        int keyPos = json.indexOf(search);
        if (keyPos < 0) return def;
        int pos = keyPos + search.length();
        while (pos < json.length() && json.charAt(pos) != ':') {
            pos++;
        }
        if (pos >= json.length()) return def;
        pos++; // skip colon
        while (pos < json.length() && json.charAt(pos) <= ' ') pos++;
        if (pos >= json.length()) return def;
        
        // Handle null as 0
        if (json.charAt(pos) == 'n') {
            return 0;
        }
        
        int start = pos;
        while (pos < json.length()
                && (Character.isDigit(json.charAt(pos)) || json.charAt(pos) == '-')) {
            pos++;
        }
        if (pos == start) return def;
        try {
            return Integer.parseInt(json.substring(start, pos));
        } catch (Exception e) {
            return def;
        }
    }
}
