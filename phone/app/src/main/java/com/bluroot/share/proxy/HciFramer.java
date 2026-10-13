package com.bluroot.share.proxy;

/**
 * Pulls HCI packets out of a byte stream. The type byte stays outside the payload.
 * Length rules match the PC app in pc/bluroot/hci.py.
 */
public final class HciFramer {
    public interface Sink {
        void onPacket(int type, byte[] payload);
    }

    public static final int COMMAND = 1;
    public static final int ACL = 2;
    public static final int SCO = 3;
    public static final int EVENT = 4;
    public static final int ISO = 5;

    private static final int NEED_TYPE = 0;
    private static final int NEED_HEADER = 1;
    private static final int NEED_BODY = 2;

    private final Sink sink;
    private final byte[] header = new byte[4];
    private byte[] body = new byte[0];
    private int state = NEED_TYPE;
    private int type;
    private int headerNeeded;
    private int headerGot;
    private int bodyNeeded;
    private int bodyGot;

    public HciFramer(Sink sink) {
        this.sink = sink;
    }

    public static int headerSize(int type) {
        switch (type) {
            case COMMAND:
            case SCO:
                return 3;
            case EVENT:
                return 2;
            case ACL:
            case ISO:
                return 4;
            default:
                return -1;
        }
    }

    public static int bodyLength(int type, byte[] header) {
        switch (type) {
            case COMMAND:
            case SCO:
                return header[2] & 0xFF;
            case EVENT:
                return header[1] & 0xFF;
            case ACL:
                return (header[2] & 0xFF) | ((header[3] & 0xFF) << 8);
            case ISO:
                // Low 14 bits are the load length. The top bits are flags.
                return ((header[2] & 0xFF) | ((header[3] & 0xFF) << 8)) & 0x3FFF;
            default:
                throw new IllegalArgumentException("bad hci type " + type);
        }
    }

    public void feed(byte[] data, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end) {
            if (state == NEED_TYPE) {
                type = data[i++] & 0xFF;
                headerNeeded = headerSize(type);
                if (headerNeeded < 0) {
                    throw new IllegalArgumentException("bad hci type " + type);
                }
                headerGot = 0;
                state = NEED_HEADER;
                continue;
            }
            if (state == NEED_HEADER) {
                int n = Math.min(headerNeeded - headerGot, end - i);
                System.arraycopy(data, i, header, headerGot, n);
                headerGot += n;
                i += n;
                if (headerGot == headerNeeded) {
                    bodyNeeded = bodyLength(type, header);
                    body = new byte[bodyNeeded];
                    bodyGot = 0;
                    state = NEED_BODY;
                    if (bodyNeeded == 0) {
                        emit();
                    }
                }
                continue;
            }
            int n = Math.min(bodyNeeded - bodyGot, end - i);
            System.arraycopy(data, i, body, bodyGot, n);
            bodyGot += n;
            i += n;
            if (bodyGot == bodyNeeded) {
                emit();
            }
        }
    }

    private void emit() {
        byte[] payload = new byte[headerNeeded + bodyNeeded];
        System.arraycopy(header, 0, payload, 0, headerNeeded);
        if (bodyNeeded > 0) {
            System.arraycopy(body, 0, payload, headerNeeded, bodyNeeded);
        }
        sink.onPacket(type, payload);
        state = NEED_TYPE;
    }
}
