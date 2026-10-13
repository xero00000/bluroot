"""Connect to the phone over USB debugging or Wi-Fi, then bridge it into BlueZ."""

from __future__ import annotations

import json
import shutil
import socket
import subprocess
import threading
from collections.abc import Callable
from typing import Any

from bluroot.bridge import Bridge
from bluroot.vhci import Vhci, VhciError, ensure_access

STATUS_PATH = "/data/local/tmp/bluroot.status"
Event = Callable[[str, Any], None]


class LinkError(Exception):
    pass


def adb_devices() -> list[str]:
    if not shutil.which("adb"):
        raise LinkError("adb is not installed")
    result = subprocess.run(["adb", "devices"], text=True, capture_output=True, timeout=8)
    if result.returncode != 0:
        raise LinkError((result.stderr or result.stdout or "adb devices failed").strip())
    serials = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def read_phone_status(serial: str | None = None) -> dict[str, Any]:
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += ["shell", "su", "-c", f"cat {STATUS_PATH}"]
    result = subprocess.run(command, text=True, capture_output=True, timeout=20)
    text = (result.stdout or "") + (result.stderr or "")
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < start:
        raise LinkError(
            "The phone is not sharing yet. Open BluRoot on the phone and tap Start. "
            "If a root prompt appeared, allow it."
        )
    try:
        status = json.loads(text[start : end + 1])
    except json.JSONDecodeError as error:
        raise LinkError(f"Could not read the phone status ({error})") from error
    if not isinstance(status, dict):
        raise LinkError("Phone status was not a record")
    return status


def open_phone(host: str, port: int, token: str, timeout: float = 5) -> socket.socket:
    try:
        sock = socket.create_connection((host, port), timeout)
    except OSError as error:
        raise LinkError(f"Cannot reach the phone at {host}:{port} ({error})") from error
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    try:
        try:
            hello = f"BLUROOT 1 {token}\n".encode("ascii")
        except UnicodeEncodeError as error:
            raise LinkError("The code is letters and digits") from error
        sock.sendall(hello)
        line = _read_line(sock)
    except (OSError, UnicodeError, TimeoutError) as error:
        sock.close()
        raise LinkError(f"Handshake failed ({error})") from error
    if line != "OK":
        sock.close()
        raise LinkError("The phone rejected the code")
    sock.settimeout(None)
    return sock


def _read_line(sock: socket.socket) -> str:
    buf = bytearray()
    while len(buf) < 64:
        chunk = sock.recv(1)
        if not chunk:
            raise ConnectionError("closed during handshake")
        if chunk == b"\n":
            return buf.decode("ascii")
        if chunk != b"\r":
            buf += chunk
    raise ConnectionError("handshake line is too long")


class Session:
    def __init__(self, on_event: Event) -> None:
        self._on_event = on_event
        self._bridge: Bridge | None = None
        self._thread: threading.Thread | None = None
        self._serial: str | None = None
        self._forward_port: int | None = None
        self._lock = threading.Lock()

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start_usb(self, serial: str | None, port: int, code: str) -> None:
        serials = adb_devices()
        if serial:
            if serial not in serials:
                raise LinkError(f"{serial} is not an authorized adb device")
        elif len(serials) == 1:
            serial = serials[0]
        elif not serials:
            raise LinkError("No phone is connected by USB with USB debugging allowed")
        else:
            raise LinkError("More than one phone is connected. Pick one: " + ", ".join(serials))
        status = read_phone_status(serial)
        self._check_status(status)
        port = int(status.get("port") or port)
        token = code.strip() or str(status.get("token") or "")
        if not token:
            raise LinkError("Enter the code shown on the phone")
        self._forward(serial, port)
        try:
            self._start("127.0.0.1", port, token, serial)
        except Exception:
            self._remove_forward()
            raise

    def start_wifi(self, host: str, port: int, code: str) -> None:
        token = code.strip()
        if not token:
            raise LinkError("Enter the code shown on the phone")
        self._start(host.strip(), port, token, None)

    def stop(self) -> None:
        with self._lock:
            bridge = self._bridge
            self._bridge = None
        if bridge is not None:
            bridge.close()
        if self._thread is not None:
            self._thread.join(timeout=2)
            self._thread = None
        self._remove_forward()
        self._on_event("status", "Stopped")

    def _check_status(self, status: dict[str, Any]) -> None:
        state = status.get("state")
        if state == "error":
            raise LinkError(str(status.get("message") or "The phone proxy failed"))
        if state == "connected":
            raise LinkError("The phone already has a PC connected")
        if state not in ("waiting", "starting"):
            raise LinkError("Start sharing on the phone first")

    def _start(self, host: str, port: int, token: str, serial: str | None) -> None:
        if self.running:
            raise LinkError("Already connected")
        self._on_event("status", "Preparing the virtual Bluetooth adapter")
        try:
            ensure_access()
        except VhciError as error:
            raise LinkError(str(error)) from error
        self._on_event("status", f"Connecting to {host}:{port}")
        sock = open_phone(host, port, token)
        try:
            device = Vhci.open()
        except OSError as error:
            sock.close()
            raise LinkError(f"Could not open /dev/vhci ({error})") from error

        def on_log(line: str) -> None:
            self._on_event("log", line)

        def on_index(index: int) -> None:
            self._on_event("index", index)

        def on_counts(to_phone: int, to_pc: int) -> None:
            self._on_event("counts", (to_phone, to_pc))

        bridge = Bridge(sock, device, on_log, on_index, on_counts)
        self._serial = serial
        self._forward_port = port if serial else None

        def run() -> None:
            try:
                self._on_event("status", "Connected. Pair devices from this PC.")
                self._on_event("log", "HCI bridge is running")
                bridge.run()
                self._on_event("status", "Disconnected")
            finally:
                self._remove_forward()
                self._on_event("stopped", None)

        with self._lock:
            self._bridge = bridge
        self._thread = threading.Thread(target=run, name="bluroot-session", daemon=True)
        self._thread.start()

    def _forward(self, serial: str, port: int) -> None:
        self._remove_forward()
        command = ["adb", "-s", serial, "forward", f"tcp:{port}", f"tcp:{port}"]
        result = subprocess.run(command, text=True, capture_output=True, timeout=8)
        if result.returncode != 0:
            raise LinkError((result.stderr or "adb forward failed").strip())
        self._serial = serial
        self._forward_port = port

    def _remove_forward(self) -> None:
        if self._serial and self._forward_port:
            subprocess.run(
                ["adb", "-s", self._serial, "forward", "--remove", f"tcp:{self._forward_port}"],
                text=True,
                capture_output=True,
                timeout=8,
            )
        self._serial = None
        self._forward_port = None
