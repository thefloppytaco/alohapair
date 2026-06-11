package com.thefloppytaco.alohapair;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AlohaPair — pair and reconnect Bluetooth accessories (game controllers, etc.) on discontinued
 * Meta Portal devices, whose stock Bluetooth UI can't discover BLE peripherals. By Moshe Baum.
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private BtPair bt;
    private LinearLayout list;

    static class Acc { String name, mac; Acc(String n, String m) { name = n; mac = m; } }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("alohapair", MODE_PRIVATE);
        bt = new BtPair(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        sv.addView(root);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("AlohaPair");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Bluetooth accessories for revived Meta Portals");
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(10));
        root.addView(sub);

        TextView tip = new TextView(this);
        tip.setText("Note: this Portal can't auto-reconnect Bluetooth devices, so each session you put "
                + "your accessory in pairing mode (fast flash) and tap Connect. It then works until it "
                + "powers off.");
        tip.setTextColor(0xFF8A6D00);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setPadding(dp(12), dp(10), dp(12), dp(10));
        tip.setBackgroundColor(0xFFFFF3D6);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(16);
        tip.setLayoutParams(tlp);
        root.addView(tip);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Button add = bigButton("+  Add accessory");
        add.setOnClickListener(v -> addDialog());
        root.addView(add);

        Button reset = new Button(this);
        reset.setText("Reset Bluetooth  (fixes stuck pairing)");
        reset.setOnClickListener(v -> {
            reset.setEnabled(false);
            reset.setText("Resetting Bluetooth…");
            bt.resetBluetooth(() -> { reset.setEnabled(true); reset.setText("Reset Bluetooth  (fixes stuck pairing)"); render(); toast("Bluetooth reset — try connecting again."); });
        });
        root.addView(reset);

        Button help = new Button(this);
        help.setText("How do I find my device's address?");
        help.setOnClickListener(v -> helpDialog());
        root.addView(help);

        render();
    }

    @Override protected void onResume() { super.onResume(); render(); }

    // ---- accessory storage ----
    private List<Acc> load() {
        List<Acc> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("accessories", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Acc(o.getString("name"), o.getString("mac")));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void save(List<Acc> accs) {
        JSONArray a = new JSONArray();
        try {
            for (Acc x : accs) { JSONObject o = new JSONObject(); o.put("name", x.name); o.put("mac", x.mac); a.put(o); }
        } catch (Throwable ignored) {}
        prefs.edit().putString("accessories", a.toString()).apply();
    }

    // ---- UI ----
    private void render() {
        list.removeAllViews();

        if (bt == null || BluetoothAdapter.getDefaultAdapter() == null) {
            list.addView(note("This device has no Bluetooth adapter."));
            return;
        }
        if (!bt.isReady()) {
            list.addView(note("Bluetooth is off."));
            Button on = bigButton("Turn Bluetooth on");
            on.setOnClickListener(v -> { BluetoothAdapter.getDefaultAdapter().enable(); v.postDelayed(this::render, 1500); });
            list.addView(on);
            return;
        }

        List<Acc> accs = load();
        if (accs.isEmpty()) {
            list.addView(note("No accessories yet. Tap “Add accessory”, give it a name and its "
                    + "Bluetooth address, put it in pairing mode, then Connect."));
            return;
        }

        for (Acc acc : accs) list.addView(card(acc, accs));
    }

    private View card(Acc acc, List<Acc> accs) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackgroundColor(0xFFF2F2F2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(acc.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        c.addView(name);

        boolean random = BtPair.looksRandom(acc.mac);
        boolean bonded = bt.isBonded(acc.mac);
        boolean connected = bt.connectionState(acc.mac) == 2;
        TextView meta = new TextView(this);
        meta.setText(acc.mac.toUpperCase() + "   •   " + (random ? "Random addr" : "Public addr")
                + (bonded ? "   •   paired" : ""));
        meta.setTextColor(Color.GRAY);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.addView(meta);

        final TextView status = new TextView(this);
        status.setPadding(0, dp(6), 0, dp(8));
        if (connected) { status.setText("● Connected"); status.setTextColor(0xFF1B7F2A); }
        else if (random) { status.setText("⚠ Random-address devices can't connect on this Portal"); status.setTextColor(0xFFB35900); }
        else { status.setText("Not connected"); status.setTextColor(Color.DKGRAY); }
        c.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button connect = new Button(this);
        connect.setText("Connect");
        connect.setEnabled(!random);
        connect.setOnClickListener(v -> {
            status.setTextColor(Color.DKGRAY);
            status.setText("Working…");
            connect.setEnabled(false);
            bt.connect(acc.mac, new BtPair.Listener() {
                public void onStatus(String m) { status.setText(m); }
                public void onResult(boolean ok, String detail) {
                    status.setText((ok ? "● " : "") + detail);
                    status.setTextColor(ok ? 0xFF1B7F2A : 0xFFB00020);
                    connect.setEnabled(true);
                }
            });
        });
        row.addView(connect);

        Button forget = new Button(this);
        forget.setText("Forget");
        forget.setOnClickListener(v -> {
            bt.forget(acc.mac, new BtPair.Listener() {
                public void onStatus(String m) {}
                public void onResult(boolean ok, String d) {}
            });
            accs.remove(acc);
            save(accs);
            render();
        });
        row.addView(forget);
        c.addView(row);
        return c;
    }

    private void addDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText name = new EditText(this);
        name.setHint("Name (e.g. Xbox Controller)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(name);

        final EditText mac = new EditText(this);
        mac.setHint("Address  AA:BB:CC:DD:EE:FF");
        mac.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(mac);

        new AlertDialog.Builder(this)
                .setTitle("Add accessory")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String m = mac.getText().toString().trim().toUpperCase();
                    if (n.isEmpty()) n = "Accessory";
                    if (!m.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                        toast("That doesn't look like a Bluetooth address (AA:BB:CC:DD:EE:FF).");
                        return;
                    }
                    List<Acc> accs = load();
                    accs.add(new Acc(n, m));
                    save(accs);
                    render();
                    if (BtPair.looksRandom(m)) {
                        new AlertDialog.Builder(this)
                                .setTitle("Heads up")
                                .setMessage("That's a random Bluetooth address. This Portal can only connect "
                                        + "to PUBLIC-address devices (most game controllers), so this one likely "
                                        + "won't connect. You can still try.")
                                .setPositiveButton("OK", null).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void helpDialog() {
        String msg =
            "Meta Portals can't scan for Bluetooth Low Energy devices, so the normal “pair from "
          + "Settings” flow never sees controllers. AlohaPair connects directly by address instead.\n\n"
          + "To get a device's address:\n"
          + "• Install “nRF Connect” (free) on an Android phone, or use a Linux PC's “bluetoothctl scan on”.\n"
          + "• Put your accessory in pairing mode and scan. Note the AA:BB:CC:DD:EE:FF address.\n"
          + "• nRF Connect also shows “Public” or “Random” next to it.\n\n"
          + "Important: this Portal can only connect to PUBLIC-address devices (e.g. an Xbox Wireless "
          + "Controller). RANDOM-address devices (most modern mice, Apple Magic, many keyboards) can't "
          + "be reached on this hardware — a limitation of the Portal's Bluetooth, not AlohaPair.\n\n"
          + "Connecting (every session): put the device in PAIRING MODE — e.g. an Xbox controller: hold "
          + "the pair button on top until it flashes FAST — then tap Connect. It takes ~20 seconds, and "
          + "the controller then works until it powers off.\n\n"
          + "Why every session? The Portal's Bluetooth can't do background scanning, so it can't "
          + "auto-reconnect a device when you just power it on (the stock Settings can't either). Pairing "
          + "mode is the one path its Bluetooth allows. Simply turning a paired controller back on will "
          + "NOT reconnect it — that's a Portal hardware limit, not AlohaPair.\n\n"
          + "If a connect fails, tap “Reset Bluetooth” and try again — after a few attempts the Portal's "
          + "Bluetooth can get stuck, and a reset clears it.";
        new AlertDialog.Builder(this).setTitle("Finding a device address").setMessage(msg)
                .setPositiveButton("Got it", null).show();
    }

    // ---- helpers ----
    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.DKGRAY);
        t.setPadding(0, dp(8), 0, dp(16));
        return t;
    }
    private Button bigButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
}
