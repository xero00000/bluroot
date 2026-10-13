#!/system/bin/sh
# Run as root. Keep the original setting on disk for interrupted-session recovery.
state_dir=${2:-/data/local/tmp}
scan_file="$state_dir/bluroot.ble-scan"
packages_file="$state_dir/bluroot.packages"

hold_stack() {
  for package in com.android.bluetooth com.google.android.bluetooth; do
    pm path "$package" >/dev/null 2>&1 || continue
    if [ ! -f "$packages_file" ] || ! sed -n "/^$package /p" "$packages_file" | read -r saved; then
      enabled=$(dumpsys package "$package" | sed -n 's/.*User 0:.* installed=true .* enabled=\([0-4]\) .*/\1/p' | sed -n '1p')
      case "$enabled" in
        0|1) printf '%s %s\n' "$package" "$enabled" >> "$packages_file" || return 1 ;;
        2|3|4) continue ;;
        *) echo "Could not save Android Bluetooth package state" >&2; return 1 ;;
      esac
    fi
    pm disable-user --user 0 "$package" || return 1
  done
}

case "$1" in
  pause)
    if [ ! -f "$scan_file" ]; then
      scan=$(settings get global ble_scan_always_enabled) || exit 1
      case "$scan" in
        null|0|1) ;;
        *) echo "Could not read background Bluetooth scanning setting" >&2; exit 1 ;;
      esac
      printf '%s\n' "$scan" > "$scan_file" || exit 1
    fi
    settings put global ble_scan_always_enabled 0 || exit 1
    cmd bluetooth_manager disable || exit 1
    # Wait for the real state, including BLE-only mode, rather than killing the
    # stack mid-shutdown and triggering Android's automatic crash recovery.
    attempts=0
    while [ "$attempts" -lt 60 ]; do
      snapshot=$(dumpsys bluetooth_manager | sed -n '/^[[:space:]]*state:/p; /^Ble app registered:/p')
      state=$(printf '%s\n' "$snapshot" | sed -n 's/^[[:space:]]*state: \([^[:space:]]*\).*$/\1/p' | sed -n '1p')
      if [ "$state" = OFF ]; then
        # Privileged OEM clients can bypass the global scan setting. Hold the
        # Android host stack disabled after clean shutdown so it cannot rebind
        # the HAL while the PC owns it. Preserve its exact enabled state.
        hold_stack || exit 1
        exit 0
      fi
      # Samsung's beacon client can register during shutdown even with global
      # scanning disabled. Stop that client only; never kill the Bluetooth stack.
      if [ "$state" = BLE_ON ]; then
        case "$snapshot" in
          *"Ble app registered:"*"com.samsung.android.beaconmanager"*)
            am force-stop com.samsung.android.beaconmanager
            ;;
        esac
      fi
      sleep 0.25
      attempts=$((attempts + 1))
    done
    echo "Android Bluetooth did not turn fully off; the radio was not taken" >&2
    exit 1
    ;;
  restore)
    if [ -f "$packages_file" ]; then
      while read -r package enabled; do
        case "$package" in
          com.android.bluetooth|com.google.android.bluetooth) ;;
          *) echo "Invalid saved Bluetooth package" >&2; exit 1 ;;
        esac
        case "$enabled" in
          0) pm default-state --user 0 "$package" || exit 1 ;;
          1) pm enable --user 0 "$package" || exit 1 ;;
          *) echo "Invalid saved Bluetooth package state" >&2; exit 1 ;;
        esac
      done < "$packages_file"
      rm -f "$packages_file"
    fi
    if [ -f "$scan_file" ]; then
      scan=$(cat "$scan_file") || exit 1
      case "$scan" in
        null) settings delete global ble_scan_always_enabled || exit 1 ;;
        0|1) settings put global ble_scan_always_enabled "$scan" || exit 1 ;;
        *) echo "Invalid saved Bluetooth scanning setting" >&2; exit 1 ;;
      esac
      rm -f "$scan_file"
    fi
    ;;
  *) echo "Usage: $0 pause|restore [state-directory]" >&2; exit 2 ;;
esac
