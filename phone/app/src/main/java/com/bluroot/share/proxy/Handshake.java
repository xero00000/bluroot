package com.bluroot.share.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** First line on the socket. HCI bytes start only after this matches. */
public final class Handshake {
    public static final String PREFIX = "BLUROOT 1 ";
    public static final int MAX_LINE = 64;

    private Handshake() {}

    public static boolean matches(String line, String token) {
        return token != null && !token.isEmpty() && line.equals(PREFIX + token);
    }

    public static String readLine(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_LINE];
        int n = 0;
        while (n < MAX_LINE) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("connection closed during handshake");
            }
            if (b == '\n') {
                return new String(buf, 0, n, StandardCharsets.US_ASCII);
            }
            if (b != '\r') {
                buf[n++] = (byte) b;
            }
        }
        throw new IOException("handshake line is too long");
    }
}
