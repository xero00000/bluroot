"""Move HCI frames between a phone socket and a virtual controller."""

from __future__ import annotations

import socket
import threading
from typing import Callable, Protocol

from bluroot.hci import VENDOR, H4Parser, HciError, controller_index


class PacketDevice(Protocol):
    def read_packet(self) -> bytes: ...

    def write_packet(self, packet: bytes) -> None: ...

    def close(self) -> None: ...


class Bridge:
    def __init__(
        self,
        sock: socket.socket,
        device: PacketDevice,
        on_log: Callable[[str], None] | None = None,
        on_index: Callable[[int], None] | None = None,
        on_counts: Callable[[int, int], None] | None = None,
    ) -> None:
        self._sock = sock
        self._device = device
        self._on_log = on_log or (lambda _line: None)
        self._on_index = on_index or (lambda _index: None)
        self._on_counts = on_counts or (lambda _to_phone, _to_pc: None)
        self._send_lock = threading.Lock()
        self._close_lock = threading.Lock()
        self._closed = False
        self._stop = threading.Event()
        self.to_phone = 0
        self.to_pc = 0

    def run(self) -> None:
        host_to_phone = threading.Thread(target=self._read_device, name="bluroot-vhci", daemon=True)
        phone_to_host = threading.Thread(target=self._read_phone, name="bluroot-phone", daemon=True)
        host_to_phone.start()
        phone_to_host.start()
        # BlueZ owns controller setup and command flow control. Injecting our
        # own HCI commands here can corrupt framing or race its initialization.
        host_to_phone.join()
        phone_to_host.join()

    def close(self) -> None:
        with self._close_lock:
            if self._closed:
                return
            self._closed = True
            self._stop.set()
        try:
            self._sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self._sock.close()
        except OSError:
            pass
        try:
            self._device.close()
        except OSError:
            pass

    def _read_phone(self) -> None:
        parser = H4Parser()
        try:
            while not self._stop.is_set():
                data = self._sock.recv(8192)
                if not data:
                    if not self._stop.is_set():
                        self._on_log("Phone link ended: the phone closed the connection")
                    break
                for frame in parser.feed(data):
                    try:
                        self._device.write_packet(frame)
                    except OSError as error:
                        if getattr(error, "errno", None) == 22:
                            raise OSError(
                                "a packet is larger than this kernel allows through /dev/vhci"
                            ) from error
                        raise
                    self.to_pc += 1
                    self._on_counts(self.to_phone, self.to_pc)
        except (OSError, HciError) as error:
            if not self._stop.is_set():
                self._on_log(f"Phone link ended: {error}")
        finally:
            self.close()

    def _read_device(self) -> None:
        try:
            while not self._stop.is_set():
                frame = self._device.read_packet()
                if not frame:
                    break
                index = controller_index(frame)
                if index is not None:
                    self._on_index(index)
                    self._on_log(f"BlueZ adapter hci{index} is up")
                    continue
                if frame[0] == VENDOR:
                    continue
                with self._send_lock:
                    self._sock.sendall(frame)
                self.to_phone += 1
                self._on_counts(self.to_phone, self.to_pc)
        except (OSError, EOFError) as error:
            if not self._stop.is_set():
                self._on_log(f"Virtual adapter closed: {error}")
        finally:
            self.close()
