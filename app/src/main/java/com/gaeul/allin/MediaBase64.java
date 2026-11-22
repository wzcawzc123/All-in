package com.gaeul.allin;

/** Base64 for media uploads and job IDs, including Android API 24 and 25. */
final class MediaBase64 {
    private static final String STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private MediaBase64() {}

    static String standard(byte[] bytes) { return encode(bytes, STANDARD, true); }
    static String urlNoPadding(byte[] bytes) { return encode(bytes, URL, false); }

    private static String encode(byte[] bytes, String alphabet, boolean padding) {
        StringBuilder result = new StringBuilder((bytes.length + 2) / 3 * 4);
        for (int i = 0; i < bytes.length; i += 3) {
            int remaining = bytes.length - i;
            int a = bytes[i] & 255;
            int b = remaining > 1 ? bytes[i + 1] & 255 : 0;
            int c = remaining > 2 ? bytes[i + 2] & 255 : 0;
            result.append(alphabet.charAt(a >>> 2)).append(alphabet.charAt(((a & 3) << 4) | (b >>> 4)));
            if (remaining > 1) result.append(alphabet.charAt(((b & 15) << 2) | (c >>> 6)));
            else if (padding) result.append('=');
            if (remaining > 2) result.append(alphabet.charAt(c & 63));
            else if (padding) result.append('=');
        }
        return result.toString();
    }

    static byte[] decodeStandard(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '=') end--;
        int padding = text.length() - end;
        int tail = end % 4;
        if (tail == 1 || (padding > 0 && (tail == 0 || padding != 4 - tail)))
            throw new IllegalArgumentException("Invalid base64 padding");
        byte[] result = new byte[(int) ((long) end * 6 / 8)];
        int bits = 0;
        int count = 0;
        int offset = 0;
        for (int i = 0; i < end; i++) {
            int value = STANDARD.indexOf(text.charAt(i));
            if (value < 0) throw new IllegalArgumentException("Invalid base64 character");
            bits = (bits << 6) | value;
            count += 6;
            if (count >= 8) {
                count -= 8;
                result[offset++] = (byte) (bits >>> count);
            }
        }
        return result;
    }
}
