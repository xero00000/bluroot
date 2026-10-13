# BluRoot

**Use a rooted Android phone as the Bluetooth adapter for a Linux PC.**

BluRoot sends Bluetooth controller traffic from your phone to Linux over USB or Wi-Fi. Your PC then sees a normal Bluetooth adapter, so you can pair devices through Linux Bluetooth settings. The phone supplies the radio; the PC manages the connections.

**Root is required on the phone. BluRoot does not root your phone for you.** The phone's own Bluetooth is unavailable while sharing.

## Use at your own risk

BluRoot is experimental software, provided **as is, without warranty**. Use it at your own risk. Success on the tested setup does not guarantee compatibility with every phone, Linux distribution, or Bluetooth device.

- The phone app runs a radio proxy as root and temporarily disables Android's Bluetooth stack and background Bluetooth scanning.
- With the default settings, SELinux is **permissive while sharing**, weakening an important Android security boundary. Its saved state is restored when sharing stops.
- If a session is interrupted, Bluetooth can remain disabled until recovery runs. Reopen BluRoot to restore the saved settings. **Stop sharing before uninstalling the app.**
- The Wi-Fi pairing code controls access but **does not encrypt the Bluetooth traffic carried over the network**. Use a trusted private network or a USB cable. Do not expose the sharing port to the internet.

Use a phone you own and are comfortable administering with root. Rooting itself can affect device security, warranty, and app compatibility; this project does not provide rooting instructions.

## Requirements

| Phone | PC |
| --- | --- |
| Already rooted Android 10 or newer | Linux with BlueZ running |
| Root manager, such as Magisk or KernelSU | Python 3.10 or newer |
| Permission to install an APK outside an app store | Tkinter for the desktop window |
| USB debugging for USB sharing | `adb` for USB; optional for Wi-Fi diagnostics |
| A compatible Android Bluetooth hardware service | `/dev/vhci` support (`CONFIG_BT_HCIVHCI`) in the kernel |

Windows and macOS clients are not included. Android 10 is the app's minimum version, not a guarantee that every Android 10+ phone works.

Common PC dependencies on Debian/Ubuntu:

```bash
sudo apt install python3 python3-tk bluez adb acl
sudo systemctl start bluetooth
```

On Fedora/Nobara:

```bash
sudo dnf install python3 python3-tkinter bluez android-tools acl
sudo systemctl start bluetooth
```

Package names can vary by distribution. An administrator prompt may appear when BluRoot prepares the virtual adapter. Run the PC client as your normal user.

## Get started

Download the files from the [latest release](https://github.com/xero00000/bluroot/releases/latest):

- **`BluRoot-v1.0.0-android.apk`** — signed phone app.
- **`BluRoot-v1.0.0-linux.tar.gz`** — Python client and launchers; no Android build needed.
- **`SHA256SUMS`** — download checksums.

### 1. Install the phone app

Transfer the APK to your phone and open it. Android may ask you to allow installation for the browser or file manager you use. With an authorized ADB connection, you can instead run:

```bash
adb install BluRoot-v1.0.0-android.apk
```

If a development/debug build is installed, stop sharing, uninstall that build, and then install the release APK. Debug and release builds use different signing keys. Future releases will use the same release key so they can update the release app.

### 2. Start sharing on the phone

Open **BluRoot**, leave both switches enabled, and tap **Start sharing**. Allow the root request in your root manager. When ready, the app shows a connection code, Wi-Fi address, and port.

The default port is `8877`. The switches control pausing Android Bluetooth and allowing permissive SELinux. These defaults are the tested configuration; changing them can prevent the proxy from obtaining the radio.

### 3. Start the PC client

Extract the Linux download and run the launcher:

```bash
tar -xzf BluRoot-v1.0.0-linux.tar.gz
cd BluRoot-v1.0.0-linux
./bluroot.sh
```

If you cloned this repository, run `./bluroot.sh` from the repository root instead.

**USB:** Connect a data cable, enable USB debugging, and approve the PC on the phone. Select **USB** in BluRoot and click **Connect**. The client reads the code through ADB.

**Wi-Fi:** Use the same trusted network on both devices. Select **Wi-Fi**, choose the discovered phone or enter its address, type the code shown on the phone, and click **Connect**. Wireless ADB is separate and is not required for Wi-Fi sharing.

Command-line alternatives:

```bash
./bluroot --usb
./bluroot --host 192.168.1.20 --code K7QM2P
```

If ADB lists multiple devices/connections, select one explicitly:

```bash
./bluroot --usb --serial YOUR_ADB_DEVICE_SERIAL
```

### 4. Pair from Linux

When BluRoot reports an adapter such as `hci0`, open the PC's normal Bluetooth settings. Enable that adapter, put your peripheral into pairing mode, and pair from the PC. If the PC has several adapters, choose the one reported by BluRoot.

Existing phone pairings are not imported. Devices are paired to Linux while sharing. One PC can connect at a time.

### 5. Stop sharing

Disconnect the PC client, then tap **Stop sharing on the phone**. Disconnecting the PC alone leaves the phone ready for another connection; it does not return the radio to Android. The phone's Stop action restores Android Bluetooth, background scanning, the stack's original enabled state, and SELinux.

## Verified setup and limitations

The initial release was tested with a **Samsung Galaxy S25 Ultra (SM-S938U1), Android 16, KernelSU root, and a Nobara Linux PC**, with the bridge over Wi-Fi and diagnostics over wireless ADB.

- Controller initialization and discovery worked.
- `onn. True Wireless ANC` headphones paired and played audio successfully in manual testing before the final Android ownership guard was added.
- Discovery continued with the phone screen off in an earlier bridge test.
- The final ownership guard passed a 60-second bridge/discovery test with no additional Android Bluetooth stack crashes. Sustained headphone playback on the final signed release APK has not yet been verified.
- Normal stopping and recovery after an interrupted share restored Bluetooth, background scanning, the stack's enabled state, and SELinux.
- All **33 automated tests passed**: 15 PC tests, 9 radio handoff/recovery tests, and 9 Android framing/handshake tests.

Other phones, USB sharing, keyboards, mice, and BLE peripherals have not been hardware-verified for this release. Headset microphone/call audio, SCO, and LE Audio/ISO support are limited or unverified. Long sessions, battery-saving modes, network interruptions, and multi-user Android setups need further testing.

## Troubleshooting

**Root denied or radio unavailable:** Check BluRoot's grant in the root manager. Keep both default switches on. Some phones expose a different Bluetooth hardware service and may fail to initialize.

**No `/dev/vhci`:** Try `sudo modprobe hci_vhci`. If the device still does not exist, your kernel may lack `CONFIG_BT_HCIVHCI`. BluRoot normally grants your user access with an ACL. Without ACL tools, its permission fallback makes `/dev/vhci` writable by local users.

**USB cannot find the phone:** Run `adb devices`, unlock the phone, and approve debugging. Unauthorized/offline devices will not connect. Wireless ADB can expose one phone more than once; use `--serial` to select a connection.

**Wi-Fi cannot reach the phone:** Enter the address shown in the phone app. Check router client isolation and firewall rules for TCP `8877`. Discovery uses UDP `8878`; manually entering the address works without broadcasts.

**Pairing/audio disconnects:** Check whether BluRoot itself disconnected. If the bridge remains up, investigate the Bluetooth device or Linux audio setup. Compare with USB if available, and include phone and PC logs in an issue. USB is implemented but not hardware-verified on the release test setup.

**Phone Bluetooth stays disabled after an interruption:** Reopen BluRoot to recover the saved settings. Rebooting stops a stranded proxy, but disabled package/scanning settings can persist, so reopen BluRoot after a reboot. Keep the app installed until recovery completes.

The phone app displays recent proxy messages. For diagnostics over authorized ADB:

```bash
adb shell su -c 'cat /data/local/tmp/bluroot.log'
```

Review logs before posting them publicly: they can contain names, addresses, or connection codes. [Report an issue](https://github.com/xero00000/bluroot/issues) with the phone model, Android version, root manager, Linux distribution, connection type, and where the failure occurs.

## Build and test from source

The PC client runs directly from the repository. To install its command into a virtual environment:

```bash
python3 -m venv .venv
.venv/bin/pip install ./pc
.venv/bin/bluroot --help
```

The optional `bluroot.desktop` launcher expects an installed `bluroot` command on your desktop session's `PATH`; the source launcher works without it.

For Android, install **JDK 17**, SDK platform **34**, and build tools **34.0.0**. Set these paths for your system:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk
cd phone
./gradlew --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run tests from the repository root:

```bash
PYTHONPATH=pc python3 -m unittest discover -s pc/tests -v
python3 -m unittest discover -s phone/tests -v
cd phone
./gradlew --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest
```

`assembleRelease` builds an unsigned APK for maintainers to sign. The public APK uses a separate private signing key kept outside the repository.

## How it works

A root proxy takes ownership of Android's Bluetooth hardware abstraction layer (HAL). It forwards HCI packets, the low-level commands/data exchanged with a Bluetooth controller, over TCP. Linux's `/dev/vhci` presents them as a virtual adapter for BlueZ to manage.

BluRoot waits for Android Bluetooth to shut down, saves the package and background scanning settings, and holds Android's stack disabled while the PC owns the radio. This prevents background services from starting a competing stack. The PC bridge leaves controller setup and command flow control to BlueZ.

The air link remains Bluetooth. USB/Wi-Fi replaces only the hop between phone and PC; it does not increase a peripheral's Bluetooth speed.

## License

[Apache License 2.0](LICENSE). Android Bluetooth HAL stubs are derived from [Google's Bumble project](https://github.com/google/bumble); see [NOTICE](NOTICE).
