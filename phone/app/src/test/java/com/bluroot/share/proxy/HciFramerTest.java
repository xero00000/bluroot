package com.bluroot.share.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HciFramerTest {
    @Test
    public void eventWithPayload() {
        List<Captured> packets = feed(bytes(0x04, 0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00));
        assertEquals(1, packets.size());
        assertEquals(HciFramer.EVENT, packets.get(0).type);
        assertArrayEquals(bytes(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00), packets.get(0).payload);
    }

    @Test
    public void emptyEventBody() {
        List<Captured> packets = feed(bytes(0x04, 0x0E, 0x00));
        assertEquals(1, packets.size());
        assertArrayEquals(bytes(0x0E, 0x00), packets.get(0).payload);
    }

    @Test
    public void aclSplitAcrossReads() {
        List<Captured> packets = new ArrayList<>();
        HciFramer framer = framer(packets);
        byte[] first = bytes(0x02, 0x00);
        byte[] second = bytes(0x00, 0x02, 0x00, 0xAA, 0xBB);
        framer.feed(first, 0, first.length);
        assertEquals(0, packets.size());
        framer.feed(second, 0, second.length);
        assertEquals(1, packets.size());
        assertEquals(HciFramer.ACL, packets.get(0).type);
        assertArrayEquals(bytes(0x00, 0x00, 0x02, 0x00, 0xAA, 0xBB), packets.get(0).payload);
    }

    @Test
    public void isoLengthIgnoresFlagBits() {
        // Load length 1, with the top bits of the length word set.
        List<Captured> packets = feed(bytes(0x05, 0x00, 0x00, 0x01, 0xC0, 0x7E));
        assertEquals(1, packets.size());
        assertEquals(HciFramer.ISO, packets.get(0).type);
        assertEquals(5, packets.get(0).payload.length);
        assertEquals((byte) 0x7E, packets.get(0).payload[4]);
    }

    @Test
    public void twoPacketsInOneRead() {
        List<Captured> packets = feed(bytes(
                0x01, 0x01, 0x10, 0x00,
                0x04, 0x0E, 0x01, 0x00));
        assertEquals(2, packets.size());
        assertEquals(HciFramer.COMMAND, packets.get(0).type);
        assertEquals(HciFramer.EVENT, packets.get(1).type);
    }

    @Test
    public void badTypeResetsTheStream() {
        HciFramer framer = framer(new ArrayList<>());
        try {
            byte[] data = bytes(0x00);
            framer.feed(data, 0, data.length);
            fail("expected a bad type");
        } catch (IllegalArgumentException expected) {
            // The PC treats this the same way.
        }
    }

    private static List<Captured> feed(byte[] data) {
        List<Captured> packets = new ArrayList<>();
        framer(packets).feed(data, 0, data.length);
        return packets;
    }

    private static HciFramer framer(List<Captured> packets) {
        return new HciFramer((type, payload) -> packets.add(new Captured(type, payload)));
    }

    private static byte[] bytes(int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            data[i] = (byte) values[i];
        }
        return data;
    }

    private static final class Captured {
        final int type;
        final byte[] payload;

        Captured(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
