"""HCI-over-stream framing. Keep the length rules in step with the phone's HciFramer."""

from __future__ import annotations

import struct

COMMAND = 0x01
ACL = 0x02
SCO = 0x03
EVENT = 0x04
ISO = 0x05
VENDOR = 0xFF

# type -> (header size, length offset inside the header, struct code, mask or None)
_INFO: dict[int, tuple[int, int, str, int | None]] = {
    COMMAND: (3, 2, "B", None),
    ACL: (4, 2, "H", None),
    SCO: (3, 2, "B", None),
    EVENT: (2, 1, "B", None),
    ISO: (4, 2, "H", 0x3FFF),
}


class HciError(Exception):
    pass


class H4Parser:
    """Collect complete H4 frames, each including its type byte."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self._buffer.extend(data)
        frames: list[bytes] = []
        while True:
            frame = self._pop()
            if frame is None:
                return frames
            frames.append(frame)

    def _pop(self) -> bytes | None:
        if not self._buffer:
            return None
        packet_type = self._buffer[0]
        info = _INFO.get(packet_type)
        if info is None:
            self._buffer.clear()
            raise HciError(f"bad hci type {packet_type}")
        header_size, length_offset, code, mask = info
        if len(self._buffer) < 1 + header_size:
            return None
        body_length = struct.unpack_from("<" + code, self._buffer, 1 + length_offset)[0]
        if mask is not None:
            body_length &= mask
        total = 1 + header_size + body_length
        if len(self._buffer) < total:
            return None
        frame = bytes(self._buffer[:total])
        del self._buffer[:total]
        return frame


def controller_index(packet: bytes) -> int | None:
    """Index announced by /dev/vhci, or None when this is ordinary HCI."""
    if len(packet) == 4 and packet[0] == VENDOR:
        return packet[2] | (packet[3] << 8)
    return None
