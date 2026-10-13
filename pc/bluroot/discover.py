"""Hear the phone announce itself on the LAN."""

from __future__ import annotations

import socket
import threading
import time
from dataclasses import dataclass

BEACON_PORT = 8878


@dataclass(frozen=True)
class Phone:
    address: str
    port: int
    state: str
    name: str
    seen: float


def parse_beacon(data: bytes, sender: str) -> Phone | None:
    try:
        text = data.decode("ascii").strip()
    except UnicodeError:
        return None
    parts = text.split("\t")
    if len(parts) < 5 or parts[0] != "BLUROOT" or parts[1] != "1":
        return None
    try:
        port = int(parts[2])
    except ValueError:
        return None
    if port < 1 or port > 65535:
        return None
    return Phone(sender, port, parts[3], parts[4], time.monotonic())


class Discovery:
    def __init__(self, port: int = BEACON_PORT) -> None:
        self.port = port
        self.error = ""
        self._phones: dict[str, Phone] = {}
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="bluroot-discover", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def phones(self) -> list[Phone]:
        now = time.monotonic()
        with self._lock:
            live = [phone for phone in self._phones.values() if now - phone.seen < 5]
            self._phones = {phone.address: phone for phone in live}
            return sorted(live, key=lambda phone: phone.name.lower())

    def _run(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", self.port))
            sock.settimeout(1)
        except OSError as error:
            self.error = f"Phone discovery is off ({error})"
            sock.close()
            return
        while not self._stop.is_set():
            try:
                data, address = sock.recvfrom(512)
            except socket.timeout:
                continue
            except OSError:
                break
            phone = parse_beacon(data, address[0])
            if phone is None:
                continue
            with self._lock:
                self._phones[phone.address] = phone
        sock.close()
