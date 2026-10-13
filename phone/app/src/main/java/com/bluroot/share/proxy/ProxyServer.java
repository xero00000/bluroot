package com.bluroot.share.proxy;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Listens for the PC, then moves HCI packets between the socket and the radio. */
public final class ProxyServer implements HciRadio.Listener {
    public static final int BEACON_PORT = 8878;
    public static final String STOP_PATH = "/data/local/tmp/bluroot.stop";
    public static final String PID_PATH = "/data/local/tmp/bluroot.pid";

    private static final String TAG = "BluRoot";
    private static final int LOG_LIMIT = 30;

    private final int port;
    private final String token;
    private final String deviceName;
    private final File statusFile;
    private final File stopFile = new File(STOP_PATH);
    private final Object sendLock = new Object();
    private final Object statusLock = new Object();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private HciRadio.Link radio;
    private volatile ServerSocket server;
    private volatile Socket client;
    private volatile OutputStream clientOut;
    private volatile String state = "starting";
    private volatile String message = "Starting";
    private volatile String clientLabel = "";
    private volatile String halName = "";
    private volatile int fromHost;
    private volatile int toHost;
    private volatile boolean running = true;

    public ProxyServer(int port, String token, String deviceName, File statusFile) {
        this.port = port;
        this.token = token;
        this.deviceName = deviceName;
        this.statusFile = statusFile;
    }

    public void run() {
        writePid();
        Thread beacon = new Thread(this::beaconLoop, "bluroot-beacon");
        beacon.setDaemon(true);
        beacon.start();
        Thread stopper = new Thread(this::watchStop, "bluroot-stop");
        stopper.setDaemon(true);
        stopper.start();
        try {
            log("Taking the Bluetooth radio");
            radio = HciRadio.open(this);
            halName = radio.name();
            log("Radio is ready (" + halName + ")");
            serve();
        } catch (HciRadio.HalException error) {
            state = "error";
            message = error.getMessage();
            log(message);
            writeStatus();
        } catch (IOException error) {
            state = "error";
            message = error.getMessage() == null ? "socket failed" : error.getMessage();
            log(message);
            writeStatus();
        } finally {
            running = false;
            closeClient();
            closeServer();
            if (radio != null) {
                radio.close();
                radio = null;
            }
            if (!"error".equals(state)) {
                state = "stopped";
                message = "Stopped";
                writeStatus();
            }
        }
    }

    private void serve() throws IOException {
        ServerSocket listen = new ServerSocket();
        listen.setReuseAddress(true);
        listen.bind(new InetSocketAddress(port));
        listen.setSoTimeout(1000);
        server = listen;
        state = "waiting";
        message = "Waiting for the PC";
        log("Listening on port " + port);
        writeStatus();

        while (running && !stopFile.exists()) {
            Socket socket;
            try {
                socket = listen.accept();
            } catch (SocketTimeoutException ignored) {
                continue;
            }
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            if (!acceptClient(socket)) {
                socket.close();
                state = "waiting";
                message = "Waiting for the PC";
                clientLabel = "";
                writeStatus();
            }
        }
    }

    private boolean acceptClient(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String line;
        try {
            line = Handshake.readLine(in);
        } catch (IOException error) {
            log("Rejected a connection: " + error.getMessage());
            return false;
        }
        OutputStream out = socket.getOutputStream();
        if (!Handshake.matches(line, token)) {
            out.write("DENY\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            log("Rejected a PC with the wrong code");
            return false;
        }
        out.write("OK\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        client = socket;
        clientOut = out;
        String remote = socket.getInetAddress().getHostAddress();
        clientLabel = remote == null ? "PC" : remote;
        state = "connected";
        message = "PC connected";
        fromHost = 0;
        toHost = 0;
        log("PC connected from " + clientLabel);
        writeStatus();

        HciFramer framer = new HciFramer((type, payload) -> {
            fromHost++;
            if (radio != null) {
                radio.send(type, payload);
            }
        });
        byte[] buf = new byte[8192];
        try {
            while (running && !stopFile.exists()) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                framer.feed(buf, 0, n);
            }
        } catch (IOException error) {
            log("PC link closed: " + error.getMessage());
        } catch (IllegalArgumentException error) {
            log("Bad HCI from the PC: " + error.getMessage());
        }
        closeClient();
        state = "waiting";
        message = "Waiting for the PC";
        clientLabel = "";
        log("PC disconnected");
        writeStatus();
        return true;
    }

    @Override
    public void onControllerPacket(int type, byte[] payload) {
        byte[] frame = new byte[payload.length + 1];
        frame[0] = (byte) type;
        System.arraycopy(payload, 0, frame, 1, payload.length);
        synchronized (sendLock) {
            OutputStream out = clientOut;
            if (out == null) {
                return;
            }
            try {
                out.write(frame);
                out.flush();
                toHost++;
            } catch (IOException error) {
                Log.w(TAG, "failed to send to PC: " + error.getMessage());
            }
        }
    }

    private void watchStop() {
        while (running) {
            if (stopFile.exists()) {
                log("Stop requested");
                running = false;
                closeClient();
                closeServer();
                return;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void beaconLoop() {
        DatagramSocket udp = null;
        try {
            udp = new DatagramSocket();
            udp.setBroadcast(true);
            while (running) {
                writeStatus();
                byte[] payload = beaconText().getBytes(StandardCharsets.US_ASCII);
                sendBeacon(udp, payload, InetAddress.getByName("255.255.255.255"));
                try {
                    Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                    while (ifaces.hasMoreElements()) {
                        NetworkInterface iface = ifaces.nextElement();
                        if (!iface.isUp() || iface.isLoopback()) {
                            continue;
                        }
                        for (var address : iface.getInterfaceAddresses()) {
                            InetAddress broadcast = address.getBroadcast();
                            if (broadcast != null) {
                                sendBeacon(udp, payload, broadcast);
                            }
                        }
                    }
                } catch (SocketException ignored) {
                    // A missing interface list just skips directed broadcasts.
                }
                Thread.sleep(1000);
            }
        } catch (Exception error) {
            Log.w(TAG, "beacon stopped: " + error.getMessage());
        } finally {
            if (udp != null) {
                udp.close();
            }
        }
    }

    private void sendBeacon(DatagramSocket udp, byte[] payload, InetAddress address) {
        try {
            udp.send(new DatagramPacket(payload, payload.length, address, BEACON_PORT));
        } catch (IOException ignored) {
            // Broadcast is best-effort. USB does not need it.
        }
    }

    private String beaconText() {
        String safe = deviceName.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        return "BLUROOT\t1\t" + port + "\t" + state + "\t" + safe + "\n";
    }

    private void writePid() {
        try {
            int pid = android.os.Process.myPid();
            writeAtomic(new File(PID_PATH), Integer.toString(pid));
        } catch (IOException error) {
            Log.w(TAG, "could not write pid: " + error.getMessage());
        }
    }

    private void writeStatus() {
        JSONObject json = new JSONObject();
        List<String> lines;
        synchronized (statusLock) {
            lines = new ArrayList<>(logLines);
        }
        try {
            json.put("state", state);
            json.put("message", message);
            json.put("port", port);
            json.put("token", token);
            json.put("hal", halName);
            json.put("client", clientLabel);
            json.put("tx", fromHost);
            json.put("rx", toHost);
            json.put("addresses", new JSONArray(ipv4Addresses()));
            json.put("log", new JSONArray(lines));
            writeAtomic(statusFile, json.toString());
        } catch (Exception error) {
            Log.w(TAG, "status write failed: " + error.getMessage());
        }
    }

    private static List<String> ipv4Addresses() {
        List<String> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                        found.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            return Collections.emptyList();
        }
        return found;
    }

    private void log(String line) {
        Log.i(TAG, line);
        System.err.println(line);
        synchronized (statusLock) {
            logLines.addLast(line);
            while (logLines.size() > LOG_LIMIT) {
                logLines.removeFirst();
            }
        }
        writeStatus();
    }

    private static void writeAtomic(File target, String text) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            tmp.delete();
        }
    }

    private void closeClient() {
        Socket socket = client;
        client = null;
        clientOut = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private void closeServer() {
        ServerSocket listen = server;
        server = null;
        if (listen != null) {
            try {
                listen.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
