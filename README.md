# AlohaPair

**Use Bluetooth game controllers (and other accessories) on a discontinued Meta Portal.**

By [Moshe Baum (@thefloppytaco)](https://github.com/thefloppytaco).

Meta Portal devices ("aloha", Android 9) have a Bluetooth stack with **BLE scanning disabled** — so
the stock Settings screen can never *discover* a controller or other Bluetooth Low Energy accessory,
and pairing simply isn't possible the normal way. AlohaPair works around this by connecting to an
accessory **directly by its MAC address**, which bypasses discovery entirely.

✅ **Verified working: an Xbox Wireless Controller** — sticks, triggers, and buttons all function as a
normal gamepad on the Portal.

## How it works

The Portal's Bluetooth hardware can *connect, bond, and run HID-over-GATT* — it just can't *scan*. So
given an address, AlohaPair runs the sequence that actually links on this firmware:

```
(re)pair    removeBond → createBond(TRANSPORT_LE) → on BONDED → HID-host connect
reconnect   HID-host connect on the already-bonded device (while it's advertising)
```

The kernel then creates a real input device via `uhid`, and Android sees the accessory as a gamepad
(or keyboard, etc.).

## Use it

1. Install **AlohaPair** on the Portal (sideload the APK, or via the Immortal app store).
2. Find your accessory's Bluetooth address — install **nRF Connect** (free) on an Android phone, or use
   `bluetoothctl scan on` on Linux. (An iPhone/Mac won't show it — Apple hides BLE addresses.)
3. In AlohaPair, tap **Add accessory**, enter a name and the address, put the device in **pairing
   mode (fast flash)**, and tap **Connect**. Pairing takes ~20 seconds; it then works until it powers
   off. If a connect fails, tap **Reset Bluetooth** and retry.

### You have to pair each session (a Portal limitation)

The Portal **can't auto-reconnect** a Bluetooth device when you just power it back on — so you put it
in pairing mode and Connect at the start of each session. This isn't an AlohaPair shortcoming: the
Portal's Bluetooth has **no background scanning**, which is the mechanism phones/PCs use to
re-establish a bonded link. The stock Settings app can't reconnect them either. We tested it four
ways (device-initiated, `autoConnect`, armed direct-connect, and on a freshly-reset stack) — a
powered-on-but-bonded controller never reconnects; only a fresh pair from pairing mode does. It's
the same disabled-scanner limitation that stops the Portal discovering BLE devices at all.

## The one big limitation: public-address devices only

AlohaPair can only connect to accessories that advertise a **public** Bluetooth address. Devices with a
**random** address can't be reached on this hardware, because Android 9 has no way to set the address
type by hand and the normal way to learn it (scanning) is disabled.

- **Works:** most **game controllers** (e.g. Xbox Wireless Controller — public address).
- **Doesn't:** most modern **mice** (incl. Logitech MX), **Apple Magic** devices, and many **keyboards**
  — they use random addresses.

How to tell: nRF Connect labels the address **Public** or **Random**. (Or read the first byte — top two
bits `11`/`01` means random.) AlohaPair flags random-address entries for you.

### Tested devices

| Device | Address | Type | Result |
|---|---|---|---|
| Xbox Wireless Controller | `3C:..` | BLE, **public** | ✅ Full gamepad — sticks, triggers, buttons |
| Logitech MX Master 3 | `F7:..` | BLE, **random** | ❌ Can't connect (random address) |
| ND104 BT1 keyboard | `CC:..` | BLE, **random** | ❌ Can't connect (random; not a Classic device) |
| A true **Classic** HID keyboard/mouse | public | Classic BR/EDR | ✅ *should* work (Classic path is open; none tested yet) |

We also confirmed the dead ends so you don't have to: **USB HID** (kernel has `CONFIG_USB_HID` off),
faking a random address as public (no Android-9 API, and the scanner that would learn the type is
disabled — not even Shizuku/privileged access helps), and Classic for the BLE keyboard (it never
appears in a Classic inquiry, while real Classic devices like a TV/washer do).

> Bluetooth speakers/headsets use the *audio* (A2DP) profile, not HID — and this Portal only supports
> A2DP **Sink**, so it can't output to an external speaker regardless. AlohaPair is for HID accessories.

## Build

Requires the Android SDK (platform 28, build-tools 34). From the repo root:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Background / discovery notes

The full reverse-engineering write-up — why BLE scanning fails, the public-vs-random finding, the live
Xbox-controller proof — lives in [`docs/FINDINGS.md`](docs/FINDINGS.md). For the complete,
blow-by-blow technical record of the whole investigation (every command, error code, and dead end),
see [`docs/INVESTIGATION-LOG.md`](docs/INVESTIGATION-LOG.md).

## License

MIT © Moshe Baum — see [LICENSE](LICENSE).

*Not affiliated with, endorsed by, or sponsored by Meta. "Portal" is a trademark of Meta Platforms,
Inc., used only to identify compatible hardware.*
