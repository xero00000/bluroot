package com.bluroot.share.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HandshakeTest {
    @Test
    public void acceptsTheMatchingCode() {
        assertTrue(Handshake.matches("BLUROOT 1 K7QM2P", "K7QM2P"));
        assertFalse(Handshake.matches("BLUROOT 1 K7QM2P", "OTHER1"));
        assertFalse(Handshake.matches("BLUROOT 1 K7QM2P\n", "K7QM2P"));
    }

    @Test
    public void readsAShortLine() throws IOException {
        byte[] raw = "BLUROOT 1 ABCD\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals("BLUROOT 1 ABCD", Handshake.readLine(new ByteArrayInputStream(raw)));
    }

    @Test
    public void rejectsALineThatNeverEnds() {
        byte[] raw = new byte[80];
        java.util.Arrays.fill(raw, (byte) 'A');
        try {
            Handshake.readLine(new ByteArrayInputStream(raw));
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("expected the line limit");
    }
}
