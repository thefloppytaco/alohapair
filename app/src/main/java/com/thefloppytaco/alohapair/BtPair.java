package com.thefloppytaco.alohapair;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

/**
 * The pair / connect / reconnect flow that actually works on a Meta Portal (aloha, Android 9),
 * whose Bluetooth scanner is disabled. We connect a BLE HID accessory *by MAC address*, which
 * bypasses discovery entirely:
 *
 *   (re)pair:   removeBond -> createBond(TRANSPORT_LE) -> on BONDED -> HID-host connect
 *   reconnect:  HID-host connect on the already-bonded device (it must be advertising)
 *
 * Only works for devices that advertise a PUBLIC address (e.g. Xbox Wireless Controller).
 * RANDOM-address devices can't be reached on Android 9 (no API to set the address type, and
 * scanning — the normal way to learn it — is disabled on this hardware).
 */
public class BtPair {

    public interface Listener {
        void onStatus(String msg);
        void onResult(boolean connected, String detail);
    }

    private static final int PROFILE_HID_HOST = 4;       // BluetoothProfile.HID_HOST (hidden on API 28)
    private static final int ADDRESS_TYPE_RANDOM_TOP = 0b11;

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private BluetoothProfile hidProxy;
    private final Handler main = new Handler(Looper.getMainLooper());

    public BtPair(Context c) {
        ctx = c.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int p, BluetoothProfile proxy) { hidProxy = proxy; }
                public void onServiceDisconnected(int p) { hidProxy = null; }
            }, PROFILE_HID_HOST);
        }
    }

    public boolean isReady() { return adapter != null && adapter.isEnabled(); }

    /**
     * Cycle the Bluetooth adapter off and on. After many failed connect attempts the Portal's
     * stack can get stuck (SMP_FAIL / local-host-terminated); a reset reliably clears it.
     */
    public void resetBluetooth(Runnable done) {
        new Thread(() -> {
            try {
                if (adapter != null) { adapter.disable(); Thread.sleep(2800); adapter.enable(); Thread.sleep(4500); }
            } catch (Throwable ignored) {}
            main.post(done);
        }).start();
    }

    /** A MAC whose first octet has its top two bits set is a (static/private) random address. */
    public static boolean looksRandom(String mac) {
        try {
            int first = Integer.parseInt(mac.substring(0, 2), 16);
            int top2 = (first >> 6) & 0b11;
            return top2 == 0b11 || top2 == 0b01; // static-random or resolvable-private
        } catch (Throwable t) { return false; }
    }

    public void forget(String mac, Listener l) {
        new Thread(() -> {
            try {
                BluetoothDevice dev = adapter.getRemoteDevice(mac);
                if (dev.getBondState() != BluetoothDevice.BOND_NONE) {
                    dev.getClass().getMethod("removeBond").invoke(dev);
                }
                post(l, false, "Forgotten");
            } catch (Throwable t) { post(l, false, "Forget failed: " + t.getMessage()); }
        }).start();
    }

    /**
     * Connect the accessory. On this Portal a bonded device can NOT auto-reconnect on power-on
     * (BLE background scanning is disabled), so every session you must put it in PAIRING MODE
     * (fast flash) and we re-pair cleanly — that's the only path the Portal's Bluetooth allows.
     */
    public void connect(String mac, Listener l) {
        if (!isReady()) { post(l, false, "Bluetooth is off"); return; }
        final BluetoothDevice dev;
        try { dev = adapter.getRemoteDevice(mac); }
        catch (Throwable t) { post(l, false, "Bad address"); return; }

        status(l, "Make sure it's in PAIRING MODE (fast flash)…");
        new Thread(() -> {
            try {
                if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
                    try { dev.getClass().getMethod("removeBond").invoke(dev); Thread.sleep(2500); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            main.post(() -> doPair(dev, mac, l));
        }).start();
    }

    private void doPair(final BluetoothDevice dev, final String mac, final Listener l) {
        status(l, "Pairing… keep it fast-flashing (~20s)");
        BroadcastReceiver rx = new BroadcastReceiver() {
            boolean done = false;
            @Override public void onReceive(Context c, Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || !d.getAddress().equalsIgnoreCase(mac)) return;
                int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (st == BluetoothDevice.BOND_BONDED && !done) {
                    done = true; try { ctx.unregisterReceiver(this); } catch (Throwable ignored) {}
                    status(l, "Paired — attaching…");
                    attachHid(dev, l);
                } else if (st == BluetoothDevice.BOND_NONE && !done) {
                    done = true; try { ctx.unregisterReceiver(this); } catch (Throwable ignored) {}
                    post(l, false, looksRandom(mac)
                            ? "Failed — random-address device, can't connect on this Portal."
                            : "Failed — be sure it's fast-flashing & close, then retry (or tap Reset Bluetooth).");
                }
            }
        };
        ctx.registerReceiver(rx, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        try {
            try { dev.getClass().getMethod("createBond", int.class).invoke(dev, 2 /* TRANSPORT_LE */); }
            catch (Throwable refl) { dev.createBond(); }
        } catch (Throwable t) { post(l, false, "createBond failed: " + t.getMessage()); }
        main.postDelayed(() -> { try { ctx.unregisterReceiver(rx); } catch (Throwable ignored) {} }, 35000);
    }

    private void attachHid(BluetoothDevice dev, Listener l) {
        // Open a GATT link to wake/establish the connection, then attach the HID host.
        try {
            dev.connectGatt(ctx, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) main.postDelayed(g::discoverServices, 600);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable ignored) {}

        main.postDelayed(() -> {
            try {
                if (hidProxy == null) { post(l, false, "HID service not ready — try again"); return; }
                hidProxy.getClass().getMethod("connect", BluetoothDevice.class).invoke(hidProxy, dev);
            } catch (Throwable t) { /* connect() may return false if already connecting; poll anyway */ }
            pollHid(dev, l, 0);
        }, 1500);
    }

    private void pollHid(BluetoothDevice dev, Listener l, int n) {
        if (hidProxy == null) { post(l, false, "HID service unavailable"); return; }
        int state;
        try {
            state = (Integer) hidProxy.getClass()
                    .getMethod("getConnectionState", BluetoothDevice.class).invoke(hidProxy, dev);
        } catch (Throwable t) { state = 0; }
        if (state == BluetoothProfile.STATE_CONNECTED) { post(l, true, "Connected"); return; }
        if (n >= 12) {
            post(l, false, "Couldn't attach (turn the device on / put it in pairing mode and retry)");
            return;
        }
        status(l, "Connecting… (" + (state == 1 ? "linking" : "waiting for device") + ")");
        final int next = n + 1;
        main.postDelayed(() -> pollHid(dev, l, next), 2500);
    }

    public int connectionState(String mac) {
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(mac);
            if (hidProxy != null) {
                return (Integer) hidProxy.getClass()
                        .getMethod("getConnectionState", BluetoothDevice.class).invoke(hidProxy, dev);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    public boolean isBonded(String mac) {
        try { return adapter.getRemoteDevice(mac).getBondState() == BluetoothDevice.BOND_BONDED; }
        catch (Throwable t) { return false; }
    }

    private void status(Listener l, String m) { main.post(() -> l.onStatus(m)); }
    private void post(Listener l, boolean ok, String d) { main.post(() -> l.onResult(ok, d)); }
}
