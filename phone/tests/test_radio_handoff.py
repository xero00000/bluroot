"""Exercise radio handoff and recovery without changing the host's settings."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "app/src/main/assets/bluroot-radio.sh"


class RadioHandoffTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.setting = self.root / "setting"
        self.setting.write_text("1\n")
        self.state = self.root / "state"
        self.state.write_text("OFF\n")
        (self.root / "package").write_text("0\n")
        self.marker = self.root / "bluroot.ble-scan"
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        RADIO_TEST_DIR=str(self.root))
        self.command("settings", '''
case "$1" in
  get) cat "$RADIO_TEST_DIR/setting" ;;
  put) printf '%s\\n' "$4" > "$RADIO_TEST_DIR/setting" ;;
  delete) echo null > "$RADIO_TEST_DIR/setting" ;;
esac
''')
        self.command("cmd", 'exit "${RADIO_TEST_CMD_EXIT:-0}"\n')
        self.command("dumpsys", '''
if [ "$1" = package ]; then
  echo "User 0: installed=true hidden=false enabled=$(cat "$RADIO_TEST_DIR/package") instant=false"
  exit 0
fi
printf "Bluetooth Status\\n  state: %s\\n" "$(cat "$RADIO_TEST_DIR/state")"
[ "${RADIO_TEST_BEACON:-0}" = 1 ] && echo 'Ble app registered: {com.samsung.android.beaconmanager}'
exit 0
''')
        self.command("pm", '''
case "$1" in
  path) [ "$2" = com.android.bluetooth ] ;;
  disable-user) echo 3 > "$RADIO_TEST_DIR/package" ;;
  default-state) echo 0 > "$RADIO_TEST_DIR/package" ;;
  enable) echo 1 > "$RADIO_TEST_DIR/package" ;;
esac
''')
        self.command("am", '''
echo "$*" >> "$RADIO_TEST_DIR/am-log"
echo OFF > "$RADIO_TEST_DIR/state"
''')
        self.command("sleep", "exit 0\n")

    def command(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def run_script(self, action):
        return subprocess.run(["sh", str(SCRIPT), action, str(self.root)],
                              env=self.env, capture_output=True, text=True, timeout=60)

    def test_original_scan_setting_is_restored_including_unset(self):
        for value in ("1", "0", "null"):
            with self.subTest(value=value):
                self.setting.write_text(value + "\n")
                self.assertEqual(self.run_script("pause").returncode, 0)
                self.assertEqual(self.setting.read_text().strip(), "0")
                self.assertEqual(self.marker.read_text().strip(), value)
                self.assertEqual((self.root / "package").read_text().strip(), "3")
                self.assertEqual(self.run_script("restore").returncode, 0)
                self.assertEqual(self.setting.read_text().strip(), value)
                self.assertFalse(self.marker.exists())
                self.assertEqual((self.root / "package").read_text().strip(), "0")

    def test_explicitly_enabled_stack_is_restored_as_enabled(self):
        (self.root / "package").write_text("1\n")
        self.assertEqual(self.run_script("pause").returncode, 0)
        self.assertEqual(self.run_script("restore").returncode, 0)
        self.assertEqual((self.root / "package").read_text().strip(), "1")

    def test_originally_disabled_stack_is_left_disabled(self):
        (self.root / "package").write_text("3\n")
        self.assertEqual(self.run_script("pause").returncode, 0)
        self.assertEqual(self.run_script("restore").returncode, 0)
        self.assertEqual((self.root / "package").read_text().strip(), "3")

    def test_repeated_pause_preserves_original_value(self):
        self.assertEqual(self.run_script("pause").returncode, 0)
        self.assertEqual(self.run_script("pause").returncode, 0)
        self.assertEqual(self.marker.read_text().strip(), "1")

    def test_ble_only_state_times_out_and_remains_recoverable(self):
        self.state.write_text("BLE_ON\n")
        result = self.run_script("pause")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not turn fully off", result.stderr)
        self.assertEqual(self.run_script("restore").returncode, 0)
        self.assertEqual(self.setting.read_text().strip(), "1")

    def test_disable_failure_remains_recoverable(self):
        self.env["RADIO_TEST_CMD_EXIT"] = "1"
        self.assertNotEqual(self.run_script("pause").returncode, 0)
        self.assertEqual(self.run_script("restore").returncode, 0)
        self.assertEqual(self.setting.read_text().strip(), "1")

    def test_samsung_beacon_client_is_stopped_without_killing_stack(self):
        self.state.write_text("BLE_ON\n")
        self.env["RADIO_TEST_BEACON"] = "1"
        self.assertEqual(self.run_script("pause").returncode, 0)
        self.assertEqual((self.root / "am-log").read_text().strip(),
                         "force-stop com.samsung.android.beaconmanager")

    def test_invalid_setting_fails_before_changing_it(self):
        self.setting.write_text("permission denied\n")
        self.assertNotEqual(self.run_script("pause").returncode, 0)
        self.assertFalse(self.marker.exists())
        self.assertEqual(self.setting.read_text().strip(), "permission denied")

    def test_restore_without_marker_leaves_settings_alone(self):
        self.assertEqual(self.run_script("restore").returncode, 0)
        self.assertEqual(self.setting.read_text().strip(), "1")


if __name__ == "__main__":
    unittest.main()
