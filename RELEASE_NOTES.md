# BluRoot v1.0.0

Initial public release of BluRoot: lend a rooted Android phone's Bluetooth radio
to a Linux PC over USB or Wi-Fi, then pair devices through Linux Bluetooth settings.

## Downloads

- `BluRoot-v1.0.0-android.apk`: signed Android app, Android 10+ and root required.
- `BluRoot-v1.0.0-linux.tar.gz`: Python client with desktop and command-line launchers.
- `SHA256SUMS`: checksums for both downloads.

Follow the README for dependencies, installation, pairing, and recovery. If an
older debug APK is installed, stop sharing and uninstall it before installing
the release APK because the signing keys differ.

## Changes included

- Forward controller packets without injecting malformed setup commands.
- Leave controller setup and command flow control to BlueZ.
- Wait for Android Bluetooth to shut down cleanly before taking its radio.
- Hold Android's Bluetooth stack disabled during sharing so background services
  cannot repeatedly reopen the radio.
- Restore the original stack, background scanning, and SELinux settings on Stop
  and during abandoned-session recovery.
- Report when the phone closes the bridge connection.
- Release idle virtual-adapter reader threads promptly on Disconnect.

## Verification

Tested on a Samsung Galaxy S25 Ultra (SM-S938U1), Android 16, KernelSU, and a Nobara
Linux PC over Wi-Fi. Headphones paired and played audio successfully before the
final ownership guard. The final guard passed a 60-second bridge/discovery test
without additional Android Bluetooth crashes. Normal Stop and interrupted-share
recovery restored the saved settings. All 33 automated tests passed, and the
release APK's signature and alignment were verified.

The signed release APK has not yet been hardware-tested for sustained headphone
playback. USB, other phones/peripherals, microphone/call audio, SCO, LE Audio/ISO,
long-running sessions, and Android multi-user setups remain unverified or limited.

## Use at your own risk

Experimental software, provided as is without warranty. Root is required, and
the default configuration temporarily puts SELinux in permissive mode. Normal
stopping restores saved settings; reopen BluRoot after an interrupted share.
Stop sharing before uninstalling. Wi-Fi traffic is not encrypted by BluRoot;
use a trusted private network or USB. Compatibility is not guaranteed.

## Signing certificate

Release certificate SHA-256:

```text
193d234c7e1d1fae76c6a26924aa695a46c13bbe8033c468d1f2a65d7629bf50
```

This identifies the signing certificate, not the APK file checksum. Verify the
download file with `SHA256SUMS`.
