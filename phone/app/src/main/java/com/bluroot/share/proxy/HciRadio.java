package com.bluroot.share.proxy;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * Binds the phone's Bluetooth HCI HAL. Android's own stack must not be holding it.
 * AIDL is tried first because current phones expose that. HIDL covers older builds.
 */
public final class HciRadio {
    public interface Listener {
        void onControllerPacket(int type, byte[] payload);
    }

    public interface Link {
        String name();

        void send(int type, byte[] payload);

        void close();
    }

    public static final class HalException extends Exception {
        final boolean busy;

        public HalException(String message) {
            this(message, false);
        }

        HalException(String message, boolean busy) {
            super(message);
            this.busy = busy;
        }
    }

    private static final String TAG = "BluRoot";
    private static final long INIT_TIMEOUT_MS = 8000;

    private HciRadio() {}

    public static Link open(Listener listener) throws HalException {
        for (int attempt = 0; attempt < 8; attempt++) {
            // Samsung turns BLE back on immediately after a normal Bluetooth disable.
            releaseAndroidStack();
            try {
                Thread.sleep(attempt == 0 ? 300 : 450);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new HalException("HAL initialize was interrupted");
            }
            try {
                Link link = openOnce(listener);
                if (link != null) {
                    return link;
                }
                throw new HalException(
                        "No Bluetooth HAL is registered. Reboot the phone and try again.");
            } catch (HalException error) {
                if (!error.busy) {
                    throw error;
                }
                Log.i(TAG, "radio still busy, retry " + (attempt + 1));
            }
        }
        throw new HalException(
                "Android Bluetooth is still holding the radio. A background service turned BLE back on. Try again.",
                true);
    }

    private static Link openOnce(Listener listener) throws HalException {
        Link aidl = AidlLink.tryOpen(listener);
        if (aidl != null) {
            return aidl;
        }
        return HidlLink.tryOpen(listener);
    }

    /** Drop Android's own Bluetooth client so this process can bind the HAL. */
    private static void releaseAndroidStack() {
        try {
            Process process = new ProcessBuilder(
                    "sh", "-c",
                    "cmd bluetooth_manager disable >/dev/null 2>&1; "
                            + "cmd bluetooth_manager disableBle >/dev/null 2>&1; "
                            + "am force-stop com.android.bluetooth; "
                            + "am force-stop com.google.android.bluetooth; "
                            + "am force-stop com.samsung.android.beaconmanager"
            ).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (Exception error) {
            Log.w(TAG, "could not pause Android Bluetooth: " + error.getMessage());
        }
    }

    private static ArrayList<Byte> box(byte[] data) {
        ArrayList<Byte> list = new ArrayList<>(data.length);
        for (byte b : data) {
            list.add(b);
        }
        return list;
    }

    private static byte[] unbox(ArrayList<Byte> list) {
        byte[] data = new byte[list.size()];
        for (int i = 0; i < data.length; i++) {
            data[i] = list.get(i);
        }
        return data;
    }

    private static final class AidlLink
            extends android.hardware.bluetooth.IBluetoothHciCallbacks.Stub
            implements Link {
        private final android.hardware.bluetooth.IBluetoothHci service;
        private final Listener listener;
        private final Object lock = new Object();
        private int initStatus = -1;

        static Link tryOpen(Listener listener) throws HalException {
            IBinder binder = ServiceManager.getService(
                    "android.hardware.bluetooth.IBluetoothHci/default");
            if (binder == null) {
                Log.i(TAG, "AIDL Bluetooth HAL is absent");
                return null;
            }
            android.hardware.bluetooth.IBluetoothHci service =
                    android.hardware.bluetooth.IBluetoothHci.Stub.asInterface(binder);
            if (service == null) {
                return null;
            }
            AidlLink link;
            try {
                link = new AidlLink(service, listener);
            } catch (RuntimeException error) {
                Log.w(TAG, "AIDL callback stub failed: " + error.getMessage());
                return null;
            }
            link.initialize();
            Log.i(TAG, "Using the AIDL Bluetooth HAL");
            return link;
        }

        private AidlLink(android.hardware.bluetooth.IBluetoothHci service, Listener listener) {
            this.service = service;
            this.listener = listener;
        }

        private void initialize() throws HalException {
            try {
                service.initialize(this);
            } catch (RemoteException error) {
                throw new HalException("HAL initialize failed: " + error.getMessage());
            }
            int status;
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
                while (initStatus < 0) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        lock.wait(left);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new HalException("HAL initialize was interrupted");
                    }
                }
                status = initStatus;
            }
            if (status == android.hardware.bluetooth.Status.SUCCESS) {
                return;
            }
            String why;
            if (status < 0) {
                why = "the HAL did not answer";
            } else if (status == android.hardware.bluetooth.Status.ALREADY_INITIALIZED) {
                throw new HalException("Android Bluetooth is still holding the radio", true);
            } else if (status == android.hardware.bluetooth.Status.UNABLE_TO_OPEN_INTERFACE) {
                why = "the HAL could not open the controller";
            } else if (status == android.hardware.bluetooth.Status.HARDWARE_INITIALIZATION_ERROR) {
                why = "the controller failed its own startup";
            } else {
                why = "status " + status;
            }
            throw new HalException(why + ". Turn phone Bluetooth off, or set SELinux permissive, then try again.");
        }

        @Override
        public String name() {
            return "aidl";
        }

        @Override
        public void send(int type, byte[] payload) {
            try {
                switch (type) {
                    case HciFramer.COMMAND:
                        service.sendHciCommand(payload);
                        break;
                    case HciFramer.ACL:
                        service.sendAclData(payload);
                        break;
                    case HciFramer.SCO:
                        service.sendScoData(payload);
                        break;
                    case HciFramer.ISO:
                        service.sendIsoData(payload);
                        break;
                    default:
                        Log.w(TAG, "dropping host packet type " + type);
                        break;
                }
            } catch (RemoteException error) {
                Log.w(TAG, "HAL send failed: " + error.getMessage());
            }
        }

        @Override
        public void close() {
            try {
                service.close();
            } catch (RemoteException error) {
                Log.w(TAG, "HAL close failed: " + error.getMessage());
            }
        }

        @Override
        public void initializationComplete(int status) {
            synchronized (lock) {
                initStatus = status;
                lock.notifyAll();
            }
        }

        @Override
        public void hciEventReceived(byte[] event) {
            listener.onControllerPacket(HciFramer.EVENT, event);
        }

        @Override
        public void aclDataReceived(byte[] data) {
            listener.onControllerPacket(HciFramer.ACL, data);
        }

        @Override
        public void scoDataReceived(byte[] data) {
            listener.onControllerPacket(HciFramer.SCO, data);
        }

        @Override
        public void isoDataReceived(byte[] data) {
            listener.onControllerPacket(HciFramer.ISO, data);
        }
    }

    private static final class HidlLink
            extends android.hardware.bluetooth.V1_0.IBluetoothHciCallbacks.Stub
            implements Link {
        private final android.hardware.bluetooth.V1_0.IBluetoothHci service;
        private final Listener listener;
        private final Object lock = new Object();
        private int initStatus = -1;
        private boolean warnedIso;

        static Link tryOpen(Listener listener) throws HalException {
            android.hardware.bluetooth.V1_0.IBluetoothHci service;
            try {
                service = android.hardware.bluetooth.V1_0.IBluetoothHci.getService(false);
            } catch (NoSuchElementException notFound) {
                Log.i(TAG, "HIDL Bluetooth HAL is absent");
                return null;
            } catch (RemoteException error) {
                Log.w(TAG, "HIDL getService failed: " + error.getMessage());
                return null;
            }
            if (service == null) {
                return null;
            }
            HidlLink link = new HidlLink(service, listener);
            link.initialize();
            Log.i(TAG, "Using the HIDL Bluetooth HAL");
            return link;
        }

        private HidlLink(
                android.hardware.bluetooth.V1_0.IBluetoothHci service, Listener listener) {
            this.service = service;
            this.listener = listener;
        }

        private void initialize() throws HalException {
            try {
                service.initialize(this);
            } catch (RemoteException error) {
                throw new HalException("HAL initialize failed: " + error.getMessage());
            }
            int status;
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
                while (initStatus < 0) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        lock.wait(left);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new HalException("HAL initialize was interrupted");
                    }
                }
                status = initStatus;
            }
            if (status == android.hardware.bluetooth.V1_0.Status.SUCCESS) {
                return;
            }
            String why = status < 0 ? "the HAL did not answer" : "status " + status;
            throw new HalException(why + ". Android Bluetooth may still be running.");
        }

        @Override
        public String name() {
            return "hidl";
        }

        @Override
        public void send(int type, byte[] payload) {
            ArrayList<Byte> data = box(payload);
            try {
                switch (type) {
                    case HciFramer.COMMAND:
                        service.sendHciCommand(data);
                        break;
                    case HciFramer.ACL:
                        service.sendAclData(data);
                        break;
                    case HciFramer.SCO:
                        service.sendScoData(data);
                        break;
                    case HciFramer.ISO:
                        if (!warnedIso) {
                            warnedIso = true;
                            Log.w(TAG, "this HIDL HAL has no ISO channel; dropping ISO packets");
                        }
                        break;
                    default:
                        break;
                }
            } catch (RemoteException error) {
                Log.w(TAG, "HAL send failed: " + error.getMessage());
            }
        }

        @Override
        public void close() {
            try {
                service.close();
            } catch (RemoteException error) {
                Log.w(TAG, "HAL close failed: " + error.getMessage());
            }
        }

        @Override
        public void initializationComplete(int status) {
            synchronized (lock) {
                initStatus = status;
                lock.notifyAll();
            }
        }

        @Override
        public void hciEventReceived(ArrayList<Byte> event) {
            listener.onControllerPacket(HciFramer.EVENT, unbox(event));
        }

        @Override
        public void aclDataReceived(ArrayList<Byte> data) {
            listener.onControllerPacket(HciFramer.ACL, unbox(data));
        }

        @Override
        public void scoDataReceived(ArrayList<Byte> data) {
            listener.onControllerPacket(HciFramer.SCO, unbox(data));
        }
    }
}
