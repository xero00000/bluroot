"""Attach a virtual Bluetooth controller that BlueZ will drive."""

from __future__ import annotations

import os
import pwd
import select
import shutil
import subprocess
import threading

VHCI_PATH = "/dev/vhci"
_CREATE = bytes((0xFF, 0x00))


class VhciError(Exception):
    pass


class Vhci:
    def __init__(self, fd: int) -> None:
        self._fd = fd
        self._lock = threading.Lock()

    @classmethod
    def open(cls, path: str = VHCI_PATH) -> Vhci:
        fd = os.open(path, os.O_RDWR | os.O_NONBLOCK)
        device = cls(fd)
        # Creating the adapter is one write. The kernel answers with a 4-byte vendor frame.
        try:
            device.write_packet(_CREATE)
        except BaseException:
            device.close()
            raise
        return device

    def read_packet(self) -> bytes:
        while True:
            with self._lock:
                fd = self._fd
                if fd < 0:
                    raise EOFError("vhci closed")
                try:
                    packet = os.read(fd, 4096)
                except InterruptedError:
                    continue
                except BlockingIOError:
                    packet = None
                if packet is not None:
                    if not packet:
                        raise EOFError("vhci closed")
                    return packet
            # Closing an fd in another thread does not cancel a blocking read.
            # Poll in short intervals, then recheck ownership under the lock.
            poller = select.poll()
            poller.register(fd, select.POLLIN)
            poller.poll(250)

    def write_packet(self, packet: bytes) -> None:
        view = memoryview(packet)
        # The kernel treats each write as one HCI frame, so this must be a single call.
        while True:
            with self._lock:
                if self._fd < 0:
                    raise OSError("vhci closed")
                try:
                    written = os.write(self._fd, view)
                except InterruptedError:
                    continue
            if written != len(packet):
                raise OSError(f"short vhci write ({written} of {len(packet)})")
            return

    def close(self) -> None:
        with self._lock:
            if self._fd >= 0:
                fd = self._fd
                self._fd = -1
                os.close(fd)


def accessible(path: str = VHCI_PATH) -> bool:
    return os.path.exists(path) and os.access(path, os.R_OK | os.W_OK)


def ensure_access(path: str = VHCI_PATH) -> None:
    """Load the VHCI module and let this user open it."""
    if accessible(path):
        return
    if os.geteuid() == 0:
        _prepare_as_root(path)
        return
    try:
        user = pwd.getpwuid(os.getuid()).pw_name
    except KeyError:
        user = ""
    if not user.replace(".", "").replace("_", "").replace("-", "").isalnum():
        raise VhciError(f"cannot grant /dev/vhci to user {user!r}")
    script = f"""
set -e
modprobe hci_vhci || true
if [ ! -e {path} ]; then
  echo "This kernel has no {path}. It was built without CONFIG_BT_HCIVHCI." >&2
  exit 1
fi
if command -v setfacl >/dev/null 2>&1; then
  setfacl -m u:{user}:rw {path} && exit 0
fi
chmod 0666 {path}
"""
    command = ["pkexec", "sh", "-c", script] if shutil.which("pkexec") else ["sudo", "sh", "-c", script]
    if command[0] == "sudo" and not shutil.which("sudo"):
        raise VhciError(
            "Cannot open /dev/vhci. Run: sudo modprobe hci_vhci && sudo chmod 0666 /dev/vhci"
        )
    result = subprocess.run(command, text=True, capture_output=True)
    if result.returncode != 0 or not accessible(path):
        detail = (result.stderr or result.stdout or "").strip()
        raise VhciError(detail or "permission for /dev/vhci was not granted")


def _prepare_as_root(path: str) -> None:
    subprocess.run(["modprobe", "hci_vhci"], check=False)
    if not os.path.exists(path):
        raise VhciError(f"This kernel has no {path}. It was built without CONFIG_BT_HCIVHCI.")
    os.chmod(path, 0o666)
