# Meta Portal+ Revival & Bluetooth Investigation — Full Technical Log

**Date:** 2026-06-10
**Device:** Meta Portal+ Gen-1 — model `aloha` (`aloha_prod`), Android 9 (API 28), arm64-v8a,
build `Facebook/aloha_prod/aloha:9/PKQ1.191202.001/...:user/prod-keys`, Qualcomm APQ8098/MSM8998
(Snapdragon 835-class), ~3.8 GB RAM, 1080×1920 @ 160 dpi.
**Serial:** `[serial redacted]`
**Bluetooth name:** "[Portal name]", identity address `[adapter address redacted]`
**Host:** macOS (Apple Silicon), GitHub user `thefloppytaco`.

This log is in chronological order. Commands are reproduced as run. Negative results are kept.

---

## Phase 1 — Provisioning the Portal with Immortal

**Goal:** the device was running a non-stock Immortal build; re-provision from the official repo.

Discovery:
```bash
adb devices -l
# [serial redacted]  device  product:aloha_prod model:Portal_ device:aloha transport_id:1
```

- Local `~/immortal` checkout's origin was a **fork**: `github.com/thefloppytaco/immortal` (branch `stremio-fork`).
- Cloned the official upstream to compare/use:
  ```bash
  git clone --depth 1 https://github.com/starbrightlab/immortal ~/immortal-starbrightlab
  ```
- Official latest release: **v1.34**, `versionCode 35` (published 2026-06-10).
- Device was on Immortal **v1.30** (`versionCode 31`) — the "non-stock version".

Installed packages of note:
```
package:moe.shizuku.privileged.api
package:com.immortal.launcher          (v1.30, versionCode 31)
package:com.immortal.launcher.debug    (debuggable, different signing key — leftover)
```

Provisioning run (from `~/immortal-starbrightlab/provisioning`):
```bash
./provision.sh
```
Result — all steps succeeded:
- Installed `com.immortal.launcher` **v1.34** (versionCode 35).
- Started the silent-install daemon (`installd.sh`, runs as shell uid).
- Started Shizuku server (shell uid).
- Pushed a photo to the frame; granted permissions; enabled device-admin screen-off.
- Disabled Meta's install verifier (`com.facebook.appverifier`).
- Disabled the white-on-white installer overlay `com.facebook.aloha.rro.niu.android` (`cmd overlay`).
- Disabled OTA (`com.facebook.aloha.otaui`; `alohaotasetup` not present on this build).
- Set launcher = `com.immortal.launcher/.HomeActivity`, screensaver = `.PhotoDreamService`.

Verification:
```bash
adb shell "dumpsys package com.immortal.launcher | grep -E 'versionName|versionCode'"
#   versionCode=35  versionName=1.34
adb shell cmd shortcut get-default-launcher
#   Launcher: ComponentInfo{com.immortal.launcher/com.immortal.launcher.HomeActivity}
```

`config.env` highlights: `PREINSTALL_APKS` included Stremio + SmartTube; `SHIZUKU_APK_URL` set;
`DISABLE_INSTALLER_OVERLAY=true`; `DESKTOP_MODE=false`.

---

## Phase 2 — Device permission / security audit (3 parallel agents)

Three read-only agents ran concurrently over ADB.

**App runtime/install permissions:**
- `com.immortal.launcher` holds **`WRITE_SECURE_SETTINGS`** (granted via `pm grant` at provision time),
  `REQUEST_DELETE_PACKAGES`, device-admin (`force-lock` only), is HOME + active Dream.
- `com.immortal.launcher.debug` (debuggable, key `f43adec4` vs release `93939c5c`) **also** holds
  WRITE_SECURE_SETTINGS — flagged as the single riskiest item (debuggable + privileged perm).
- Aurora Store holds `REQUEST_INSTALL_PACKAGES` (only app that can install on its own).
- Stale `SYSTEM_ALERT_WINDOW` app-op on the launcher (set but perm not declared).
- Shizuku server live as uid `shell`.

**System provisioning state (post-reboot, boot_count=42):** every change "stuck":
```
cmd shortcut get-default-launcher        -> com.immortal.launcher
settings get secure screensaver_components -> com.immortal.launcher/.PhotoDreamService
settings get global package_verifier_enable -> 0
cmd overlay list | grep niu              -> [ ] com.facebook.aloha.rro.niu.android (disabled)
settings get global enable_freeform_support -> 1
```

**Shell privilege boundary:**
```
adb shell id      -> uid=2000(shell) ... groups=...,net_bt_admin,net_bt,inet,...,uhid context=u:r:shell:s0
adb shell getenforce            -> Enforcing
getprop ro.boot.verifiedbootstate -> green
getprop ro.boot.flash.locked    -> 1
getprop ro.debuggable           -> 0
adb root                        -> "adbd cannot run as root in production builds"
```
Install daemon: `sh /data/local/tmp/installd.sh /sdcard/Android/data/com.immortal.launcher/files/installq`,
polling the queue and running `pm install -r`. Exposure noted: queue lives on shared storage
(pre-scoped-storage Android 9), so any app with `WRITE_EXTERNAL_STORAGE` could drop an APK there.

---

## Phase 3 — Capability mapping → empirical probe

After a state-only agent pass, the user asked to **test, not infer**. Set up the Android SDK and built
a probe app.

SDK setup:
```bash
# cmdline-tools already present via brew
SDKMGR=/opt/homebrew/Caskroom/android-commandlinetools/*/cmdline-tools/bin/sdkmanager
export ANDROID_HOME=$HOME/Library/Android/sdk
yes | "$SDKMGR" --sdk_root=$ANDROID_HOME --licenses
"$SDKMGR" --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-28" "build-tools;34.0.0"
```
(First licenses attempt failed — `echo y` sends one acceptance; fixed with `yes |`.)

**Toolchain (reused for every app this session):** Gradle **8.9**, AGP **8.7.0**, `compileSdk 34`,
`minSdk 21`, `targetSdk 28`, Java 17 source, **plain framework (no AndroidX)**, `android.useAndroidX=false`.
gradle-wrapper distributionUrl pinned to `gradle-8.9-bin.zip`.

Probe project `~/portal-probe`, `com.immortal.probe`, `MainActivity.java` ran a battery on launch and
wrote `/sdcard/Android/data/com.immortal.probe/files/probe_report.md`.

First build error (wrong import) then success:
```
import java.io.HttpURLConnection;  // WRONG -> java.net.HttpURLConnection
```

Install + grant + run:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
for P in CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION WRITE_SECURE_SETTINGS READ_LOGS DUMP \
         PACKAGE_USAGE_STATS CHANGE_CONFIGURATION SET_ANIMATION_SCALE; do
  adb shell pm grant com.immortal.probe android.permission.$P
done
adb shell am start -n com.immortal.probe/.MainActivity
```

**Probe corrected two inferred claims:**
- **Camera:** `getCameraIdList()` returns **1** camera (front, 1280×720, `INFO_SUPPORTED_HARDWARE_LEVEL`
  = `LEGACY`/0). The 12 MP "smart cam" that `dumpsys media.camera` lists is **not** exposed to apps.
- **Freeform:** feature flag `android.software.freeform_window_management` = **false**, yet freeform
  *works* (proven below) via the `enable_freeform_support` dev override. Never gate on `hasSystemFeature()`.
- Mic: first run peak amplitude `1` (physical privacy switch muting); after user enabled it, peak `26035`.
- `WRITE_SECURE_SETTINGS` proven end-to-end: app wrote and read back a `Settings.Secure` value.

Freeform proven live via ADB (no app):
```bash
adb shell am start -n org.mozilla.firefox/org.mozilla.fenix.HomeActivity --windowingMode 5
# dumpsys activity activities -> Stack #2 ... mode=freeform, mBounds=Rect(480,1110-1440,1650)
```

Silent-install daemon proven end-to-end (net-zero reinstall of an already-installed APK):
```bash
Q=/sdcard/Android/data/com.immortal.launcher/files/installq
adb shell "cp <existing>/base.apk $Q/probe.apk"
# within ~2s: $Q/probe.apk.done + $Q/probe.apk.log == "Success"
```

---

## Phase 4 — Full permission census (462 platform permissions)

**Goal:** declare *all* platform permissions, attempt to grant each, record the result.

Bug #1: an ADB-side `while read p; do adb shell pm grant ... ; done < perms.txt` loop — `adb shell`
consumes the loop's stdin and 433 round-trips are unworkable. **Fix:** run the loop *on-device* in one
`adb shell` call:
```bash
adb push /tmp/all_perms.txt /data/local/tmp/perms.txt
adb shell 'while read p; do r=$(pm grant com.immortal.probe "$p" 2>&1); echo "$p|${r:-OK}"; done < /data/local/tmp/perms.txt'
```

Bug #2: `pm list permissions -f` on this firmware **omits the dangerous/runtime group entirely**
(no `protectionLevel:dangerous` lines). Had to also run `pm list permissions -d -g` (29 dangerous
perms incl. CAMERA, RECORD_AUDIO, location, contacts, calendar, phone/sms/sensors). True total =
433 (non-dangerous) + 29 (dangerous) = **462**.

**Result (authoritative, from `dumpsys package com.immortal.probe` granted=true):**

| Bucket | Count | How |
|---|--:|---|
| Total platform permissions | **462** | — |
| **Obtainable by a fresh app** | **107** | install + `pm grant` |
| · normal (auto at install) | 54 | INTERNET, BLUETOOTH, NFC, VIBRATE, WAKE_LOCK… |
| · dangerous (runtime) | 29 | `pm grant` or user prompt (PHONE/SMS/sensors are no-ops here) |
| · **elevated (development)** | **24** | `adb pm grant` only |
| **Refused (signature walls)** | **355** | need platform key or root |

The 24 elevated: `WRITE_SECURE_SETTINGS, READ_LOGS, DUMP, PACKAGE_USAGE_STATS, GET_APP_OPS_STATS,
BATTERY_STATS, SYSTEM_ALERT_WINDOW, INTERACT_ACROSS_USERS, MANAGE_ACTIVITY_STACKS, ACTIVITY_EMBEDDING,
CHANGE_CONFIGURATION, CONFIGURE_DISPLAY_BRIGHTNESS, BRIGHTNESS_SLIDER_USAGE, SET_ANIMATION_SCALE,
SET_ALWAYS_FINISH, SET_PROCESS_LIMIT, SET_DEBUG_APP, SET_MEDIA_KEY_LISTENER,
SET_VOLUME_KEY_LONG_PRESS_LISTENER, SIGNAL_PERSISTENT_PROCESSES, GET_PROCESS_STATE_AND_OOM_SCORE,
ACCESS_AMBIENT_LIGHT_STATS, INSTANT_APP_FOREGROUND_SERVICE, WRITE_EMBEDDED_SUBSCRIPTIONS`.

Saved to `~/portal-probe/PERMISSIONS.md`, `perms_held.txt` (107), `perms_refused.txt` (355).

---

## Phase 5 — Bluetooth investigation (the core of the session)

**Symptom reported:** Portal can't *see* an Xbox controller or MX Master 3 mouse, but sees an LG washer
and a Samsung TV.

### 5.1 Stack capability dump
```bash
adb shell pm list features | grep -i blue
#  android.hardware.bluetooth        (present)
#  (android.hardware.bluetooth_le    ABSENT)
adb shell dumpsys bluetooth_manager
```
Profiles present: `GattService`, `A2dpSinkService` (sink only, no source), **`HidHostService`**,
`AvrcpTargetService`, `AvrcpControllerService`. `AdapterState` shows `BLE_TURN_ON -> BleOnState ->
BREDR_STARTED -> OnState` (BLE stack functional). `GattService: mMaxScanFilters: 0`. Scanner map empty.

### 5.2 BLE scanning is DISABLED
Built `BtActivity` (classic discovery + `BluetoothLeScanner`). Every scan variant failed:
```
LE scan FAILED code=3   (SCAN_FAILED_INTERNAL_ERROR)
```
Tried (all code 3): `SCAN_MODE_LOW_LATENCY`, `SCAN_MODE_OPPORTUNISTIC`, `SCAN_MODE_LOW_POWER` + report
delay (batch), empty `ScanFilter`, `CALLBACK_TYPE_ALL_MATCHES` + `setLegacy(false)`. Deprecated
`BluetoothAdapter.startLeScan()` returned **false**. Classic discovery worked fine (found the Samsung
TV `[redacted]` type=CLASSIC, later the "Bespoke Washer" `[redacted]`).

### 5.3 Kernel HID reality
```bash
adb shell '(zcat /proc/config.gz) | grep -iE "USB_HID|UHID|HIDP|JOYSTICK_XPAD|INPUT_JOYDEV|USB_STORAGE"'
# CONFIG_HID=y, CONFIG_HID_GENERIC=y, CONFIG_UHID=y, /dev/uhid present
# CONFIG_USB_HID is NOT set     <- no USB mice/keyboards/receivers/gamepads
# CONFIG_JOYSTICK_XPAD is NOT set, CONFIG_INPUT_JOYDEV is NOT set, CONFIG_USB_STORAGE is NOT set
# CONFIG_BT_HIDP is NOT set     (Android uses uhid from userspace instead)
```
→ **USB-HID is compiled out**; the only viable accessory path is **Bluetooth HID via uhid**.

### 5.4 Wireless ADB (to free the USB port — though USB-HID turned out dead anyway)
```bash
adb tcpip 5555
adb connect <portal-ip>:5555      # all later commands use -s <portal-ip>:5555
```

### 5.5 BREAKTHROUGH — connect-by-MAC bypasses the dead scanner
The Portal can't *scan*, but it can *initiate a direct connection by address* (initiator role needs no
scanning). The working sequence (in `BtActivity`):
```java
BluetoothDevice dev = adapter.getRemoteDevice(MAC);
if (dev.getBondState()==BONDED) dev.getClass().getMethod("removeBond").invoke(dev);   // clean slate
dev.getClass().getMethod("createBond", int.class).invoke(dev, 2 /* TRANSPORT_LE */);  // reflection
// on ACTION_BOND_STATE_CHANGED == BOND_BONDED:
adapter.getProfileProxy(ctx, listener, 4 /* HID_HOST */);     // hidden profile id
hidProxy.getClass().getMethod("connect", BluetoothDevice.class).invoke(hidProxy, dev);  // reflection
```
The BT stack then creates an input device via **uhid**.

**Xbox Wireless Controller `3C:xx:xx:xx:xx:xx` (PUBLIC address) — WORKS.**
Launched: `am start -n com.immortal.probe/.BtActivity --es mac "3C:xx:xx:xx:xx:xx"`. Results:
```
HID host state: 3C:xx:xx:xx:xx:xx : 2   (CONNECTED)
dumpsys input: Device 6: Xbox Wireless Controller, Sources: 0x01000511 (gamepad)
/sys/bus/hid/devices: 0005:045E:0B13.0002   (045E:0B13 = MS Xbox controller, Bus 0005 = Bluetooth)
/dev/input/event4 created
getevent -lc 60 /dev/input/event4:
  EV_ABS ABS_X / ABS_Y (left stick), ABS_Z / ABS_RZ (right stick), ABS_GAS / ABS_BRAKE (triggers),
  EV_KEY BTN_NORTH / BTN_SELECT / BTN_GAMEPAD (buttons)
```
Full gamepad confirmed.

**Critical sequencing learned:** bond must fully complete *before* the HID connect (firing HID mid-bond
→ `reason=22`); the device must be advertising *at the moment* of connect (`reason=62` = connection
failed to establish if asleep). `autoConnect=true` made **zero** connection attempts (it needs
background scanning, which is disabled).

### 5.6 MX Master 3 (RANDOM address) — FAILS
`F7:xx:xx:xx:xx:xx`. `F7` = `11110111`, top two bits `11` = **static-random address**.
```
createBond -> BONDING -> NONE   bonding_error=SMP_FAIL,  then HCI_ERR_CONNECTION_TOUT
bt_stack: L2CA_RemoveFixedChnl ... CID: 0x0006 not connected   (LE SMP channel never came up)
```
Root cause: `getRemoteDevice()` assumes **PUBLIC** address type; the device is **RANDOM**, so the LE
connection request never matches. The normal way to learn the type is a **scan result** — scanning is
disabled. Tried the one possible API workaround:
```java
adapter.getClass().getMethod("getRemoteLeDevice", String.class, int.class).invoke(adapter, mac, 1);
// java.lang.NoSuchMethodException — getRemoteLeDevice doesn't exist until API 31
```

**The public/random rule (confirmed across 3 devices):**

| Device | First byte | Type | Result |
|---|---|---|---|
| Xbox Wireless Controller | `3C` (00…) | public | ✅ full gamepad |
| MX Master 3 | `F7` (11…) | random | ❌ SMP_FAIL/timeout |
| ND104 BT1 keyboard | `CC` (11…) | random | ❌ SMP_FAIL/timeout, not Classic |

---

## Phase 6 — AlohaPair (standalone app, github.com/thefloppytaco/alohapair)

Productized the proven flow as the user's own app for accreditation.
- Package `com.thefloppytaco.alohapair`, plain framework, MIT, repo created via
  `gh repo create thefloppytaco/alohapair --public --source . --remote origin --push`.
- `BtPair.java` (the pair/connect/reconnect logic) + `MainActivity.java` (UI: add accessory by name+MAC,
  Connect, Forget, Reset Bluetooth, help dialog; flags random addresses via `looksRandom()`).
- Verified on-device (screenshots): renders, detects public/random, paired badge.
- Releases: **v1.0**, **v1.1** (added Reset Bluetooth), **v1.2** (honest reconnect).

`BtPair.looksRandom()`:
```java
int first = Integer.parseInt(mac.substring(0,2),16);
int top2 = (first>>6)&0b11;
return top2==0b11 || top2==0b01;   // static-random or resolvable-private
```

---

## Phase 7 — Reconnect investigation (does a bonded controller auto-reconnect?)

**Answer: NO.** Tested four ways, all fail; pairing mode (fast flash) required each session.

1. **Device-initiated** (Portal idle, controller powered on): no incoming connection.
2. **`autoConnect=true`**: zero connection attempts (needs background scanning — disabled).
3. **Armed direct-connect** retry: controller never answers.
4. **Same on a freshly-reset stack**: still nothing.

Stock Settings "connect" **fails identically** (captured live):
```
HidHost.connect(3C:xx:xx...) -> GATT_Connect gatt_if=4 -> 30s -> bta_gattc_conn_cback connected=0 reason=0x0100
```
`reason=0x0100` = connection-failed-to-establish. So Settings does nothing AlohaPair doesn't.

**Why:** re-establishing a bonded BLE link normally uses the central's **background scanning** with an
accept-list. That subsystem is the same disabled scanner. A *direct* connect works without scanning but
only lands while the device advertises *openly* — which a bonded controller only does in **pairing
mode**. `createBond`'s long open-advertising window is what it catches.

**Stack-wedge + reset fix:** after many connect/bond/remove cycles (and a stock Settings attempt that
leaves a half-open HID entry, `btif_hh_connect: Device already added`), fresh bonds fail in ~300ms with
`SMP_FAIL` + `HCI_ERR_CONN_CAUSE_LOCAL_HOST` even at rssi −47. **`svc bluetooth disable; svc bluetooth
enable`** clears it; a pairing-mode connect then lands in ~6s (verified: full gamepad input after reset).
AlohaPair v1.1+ ships a **Reset Bluetooth** button (`adapter.disable()/enable()` with delays).

AlohaPair v1.2 reflects this honestly: removed the misleading "Reconnect" label, Connect always
re-pairs cleanly, prominent in-app note, updated help, README/FINDINGS document the root cause.

---

## Phase 8 — MAC verification via the MacBook

macOS hides BLE addresses while *scanning* (CoreBluetooth UUID), but shows real addresses for
*paired* devices.
```bash
brew install blueutil
blueutil -p 1                 # turn Mac BT on
blueutil --paired             # lists devices WITH addresses
```
Cross-check vs what was entered for the Portal:

| Mac name | Mac address | Entered for Portal |
|---|---|---|
| Xbox Wireless Controller | `3C:xx:xx:xx:xx:xx` | `3C:xx:xx:xx:xx:xx` ✅ exact (why it worked) |
| ND104 BT1 (keyboard) | `CC:xx:xx:xx:xx:xx` | `CC:xx:xx:xx:xx:xx` ❌ |
| MX Master 3 Mac | `F7:xx:xx:xx:xx:xx` | `…xx` / `…xx` ❌ |

**Caveat:** multi-host devices (MX Master "BT1/BT2/BT3" slots) use a **different address per host-slot**,
so the Mac's value is for the Mac's slot — the Portal's slot can legitimately differ. Either way the
addresses are random → still won't connect.

---

## Phase 9 — Keyboard: is it Classic? (would sidestep the random-address wall)

Classic devices use public addresses and the Portal does Classic HID fine. Tested the corrected
keyboard address `CC:xx:xx:xx:xx:xx` over **BR/EDR**:
```
createBond(TRANSPORT_BREDR=1) -> btif_dm_auth_cmpl_evt: Pairing timeout; retrying (2)...(1)...
bonding_error=HCI_ERR_PAGE_TIMEOUT     (Classic page — no device answered)
```
Then a pure Classic **inquiry** (with Mac BT off to free the keyboard, Portal stack reset):
```
CLASSIC: Bespoke Washer [[redacted]] type=CLASSIC rssi=-95    <- real Classic device shows up
(keyboard CC:xx:xx NEVER appears in Classic inquiry)
```
**Conclusion: the keyboard is NOT a Classic device** (its Mac link is BLE; `blueutil`'s "master" label
was misleading). It is BLE-random → unsupported. No Classic back door.

---

## Phase 10 — Final solution search for random-address BLE

Pressure-tested every escape hatch on-device (not theory):

1. **Set random address type via API** — enumerated the real method surface on this build via reflection
   (`mode=reflect`): `BluetoothDevice` has `connectGatt`(×4), `createBond()`, `createBond(int)`,
   `createBondOutOfBand(int,OobData)`, `getType()` — **none take/set an address type**;
   `getRemoteLeDevice` is **absent**. `BluetoothAdapter` has the LE scan/advertise methods but nothing to
   set a random address for a connect. ❌
2. **Inject `AddrType=1` into bt_config.conf** —
   ```
   adb shell ls /data/misc/bluetooth/        -> Permission denied
   adb shell id -> groups do NOT include bluetooth (gid 1002)
   ```
   Shell (and Shizuku, which is shell-uid) cannot read/write it. ❌
3. **Re-enable the scanner via config/property** — `/system/etc/bluetooth/bt_stack.conf` is stock
   (all relevant lines commented), lives in read-only `/system`; no `getprop` toggles it
   (`ro.bluetooth.library_name=libbluetooth_qti.so`). The disable is controller/stack-level. ❌
4. **All of the above require root** — `adb root` rejected; bootloader `flash.locked=1`, Verified Boot
   green, dm-verity + SELinux enforcing. ❌

**Definitive conclusion:** random-address BLE cannot be connected on this Portal by any on-device means.
The blocker is a missing Android-9 API combined with the disabled scanner (which is how the type would
normally be learned), and neither shell nor Shizuku can reach the config/firmware that would change it.
Root would fix it; the locked bootloader makes root impossible.

**Unverified idea (explicitly not proven):** an external bridge (Raspberry Pi Zero 2 W / ESP32) that
connects to the random-address device as a BLE central and re-presents it to the Portal as a **Classic**
BT HID device. Plausible in principle (Classic HID works on the Portal) but **not tested**.

---

## Final results matrix

| Capability | Status |
|---|---|
| Public-address BLE HID (e.g. Xbox controller) | ✅ Works — full gamepad via connect-by-MAC + uhid |
| Classic BT HID (true BR/EDR keyboard/mouse) | ✅ Should work (Classic path open); none tested |
| Random-address BLE HID (MX Master, ND104 kbd) | ❌ Unfixable on-device (no API / scanner off / no root) |
| Auto-reconnect of a bonded controller | ❌ Not possible (no background scanning); pair each session |
| USB HID (any) | ❌ Kernel `CONFIG_USB_HID` off |
| BLE scanning / discovery | ❌ `SCAN_FAILED_INTERNAL_ERROR` for every mode |
| Faking random→public (incl. Shizuku) | ❌ No API, config unwritable, no root |

## Artifacts produced
- `~/portal-probe/` — capability probe (`com.immortal.probe`), `CAPABILITIES.md`, `PERMISSIONS.md`,
  `BLUETOOTH.md`, `run-probe.sh`, perms lists.
- `~/alohapair/` — **AlohaPair** app (`com.thefloppytaco.alohapair`), pushed to
  **github.com/thefloppytaco/alohapair** (v1.0/v1.1/v1.2 releases with installable APK), README +
  `docs/FINDINGS.md` + tested-devices table.
- Android SDK installed at `~/Library/Android/sdk` (platform-28, build-tools 34).

## Key reusable facts
- Working toolchain: Gradle 8.9 + AGP 8.7.0, plain framework, minSdk 21 / targetSdk 28, compileSdk 34.
- Connect-by-MAC sequence (the one thing that links): `removeBond → createBond(TRANSPORT_LE) → on BONDED
  → HID host proxy(id 4).connect()` (all via reflection); device must be advertising openly (pairing mode).
- Recovery when the stack wedges: `svc bluetooth disable; svc bluetooth enable`.
- Address type: first byte top-two-bits `11`/`01` = random (won't connect); else public (works).
