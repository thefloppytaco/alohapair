# Bluetooth accessories on the Portal+ — what works, and how

## TL;DR
- **BLE scanning/discovery is disabled** on this Portal (every scan variant fails `SCAN_FAILED_INTERNAL_ERROR`; the stock Settings UI can't see BLE peripherals). This is why the MX Master 3 and Xbox controller are "invisible."
- **BLE connect + bond + HID-over-GATT (HOGP) WORK if you connect by MAC address**, bypassing the broken scanner.
- **PROVEN:** an **Xbox Wireless Controller** (045E:0B13) is fully functional over Bluetooth — sticks (`ABS_X/Y`), triggers (`ABS_GAS/BRAKE`), buttons (`BTN_*`) all emit Linux input events; `dumpsys input` lists it as a gamepad (`Sources: 0x01000511`).
- **USB HID is NOT available** (`CONFIG_USB_HID` is compiled out of the kernel), so USB mice/keyboards/receivers/wired controllers do **not** work. Bluetooth is the only accessory path.
- The kernel supports HID input via **uhid** (`CONFIG_UHID=y`, `/dev/uhid`), which is how the BT stack creates the input device.

## The working procedure (connect-by-MAC)
The stock pairing UI relies on scanning, which is dead. Instead:

1. **Get the device's Bluetooth MAC** once, from another device that exposes real MACs (Android + nRF Connect, or Linux `bluetoothctl scan on`). iPhone/macOS hide it.
2. Put the accessory in **pairing mode** (advertising).
3. From an app holding `BLUETOOTH`/`BLUETOOTH_ADMIN` (+ location for completeness):
   ```
   BluetoothDevice dev = adapter.getRemoteDevice(MAC);
   if (dev.getBondState()==BONDED) dev.removeBond();   // clean slate (reflection)
   dev.createBond(TRANSPORT_LE);                        // reflection: createBond(int)
   // on ACTION_BOND_STATE_CHANGED == BONDED:
   hidHostProxy.connect(dev);                           // reflection: BluetoothHidHost.connect()
   ```
   Bonding can take up to ~20s (SMP); keep the device advertising the whole time.
4. On success the BT stack creates a `/dev/input/eventN` node via uhid and `dumpsys input` lists the device. Verify with `getevent -lc 60 /dev/input/eventN` while pressing buttons.

### Critical sequencing notes (learned the hard way)
- **Bond must fully complete BEFORE the HID connect** — firing HID-connect mid-bond fails (`reason=22`).
- The device must be **advertising at the moment of connect** — a bonded-but-asleep device gives `reason=62` (connection failed to establish). `autoConnect=true` did *not* reliably initiate on this stack; a direct connect with the device actively advertising is what works.
- The HID host is reached via the profile proxy id **4** (`getProfileProxy(ctx, listener, 4)`), and `connect()` is a hidden/greylist method (reflection works; no BLUETOOTH_PRIVILEGED needed here).

## Verified hardware/stack facts
- Profiles present: `GattService`, `A2dpSinkService`, **`HidHostService`**, `Avrcp*`.
- `getBluetoothLeScanner()` returns non-null but every `startScan()` → code 3; legacy `startLeScan()` → false. Controller reports `mMaxScanFilters: 0`.
- Kernel: `CONFIG_HID=y`, `CONFIG_HID_GENERIC=y`, `CONFIG_UHID=y`, **`CONFIG_USB_HID` not set**, `CONFIG_JOYSTICK_XPAD` not set, `CONFIG_INPUT_JOYDEV` not set, `CONFIG_USB_STORAGE` not set.

## THE KEY LIMITATION: public-address devices only

Connect-by-MAC works **only for BLE devices that use a PUBLIC address**. Devices with a
**RANDOM address cannot be connected** on this Portal, because:
- `BluetoothAdapter.getRemoteDevice(mac)` on Android 9 always assumes address type = PUBLIC.
- The API to specify a random type, `getRemoteLeDevice(String, int)`, **does not exist until API 31**
  (confirmed `NoSuchMethodException` on this firmware).
- Normally you'd learn a device's address type from a **scan result** — but scanning is disabled here.
- So a random-address device's connection request goes to a public address that nothing answers →
  30s timeout, `CID 0x0006 not connected`, `SMP_FAIL` (the link never comes up; SMP never runs).

**How to tell which a device is:** the first address byte. Top two bits `11` (e.g. `F7:..`) or `01`
= **random**; otherwise public. nRF Connect labels it "Public"/"Random" directly.

| Device | Address | Result |
|---|---|---|
| Xbox Wireless Controller (`3C:FA:06:..`) | **Public** | ✅ connects, bonds, full gamepad input |
| MX Master 3 (`F7:C4:46:..`) | **Random (static)** | ❌ unreachable by-MAC on Android 9 |

Practically: **game controllers tend to use public addresses and work**; privacy-conscious
peripherals (most modern mice incl. Logitech MX, Apple Magic devices, many BLE keyboards) use random
addresses and **won't** connect on this Portal. A BLE accessory advertising a *public* address is the
requirement.

## Open questions to test next
- **Auto-reconnect:** the controller did NOT auto-reconnect after power-cycle, and the stock Settings
  "connect" fails — so the Immortal feature must own reconnect (re-run our connect on the bonded
  device while it advertises). Confirm whether a bonded-device `HidHost.connect()` while it's
  advertising is enough, vs a full re-bond.
- Whether `BTN_MODE` (Xbox/Guide button) and rumble are exposed.

## Repro tooling
`com.immortal.probe` `BtActivity` does all of the above. Launch:
`adb shell am start -n com.immortal.probe/.BtActivity --es mac "AA:BB:CC:DD:EE:FF"`
Report at `/sdcard/Android/data/com.immortal.probe/files/bt_report.md`.
