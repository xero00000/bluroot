import socket
import threading
import time
import unittest
from queue import Queue

from bluroot.bridge import Bridge
from bluroot.discover import parse_beacon
from bluroot.link import LinkError, open_phone


class FakeDevice:
    def __init__(self) -> None:
        self.written: list[bytes] = []
        self._incoming: Queue[bytes | None] = Queue()
        self.closed = False

    def write_packet(self, packet: bytes) -> None:
        if self.closed:
            raise OSError("closed")
        self.written.append(packet)

    def read_packet(self) -> bytes:
        item = self._incoming.get()
        if item is None:
            return b""
        return item

    def push(self, packet: bytes) -> None:
        self._incoming.put(packet)

    def close(self) -> None:
        self.closed = True
        self._incoming.put(None)


class BridgeTest(unittest.TestCase):
    def test_frames_move_both_ways_and_vendor_stays_local(self):
        phone, pc = socket.socketpair()
        device = FakeDevice()
        indexes: list[int] = []
        bridge = Bridge(pc, device, on_index=indexes.append)
        thread = threading.Thread(target=bridge.run, daemon=True)
        thread.start()

        event = bytes((0x04, 0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00))
        phone.sendall(event)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline and not device.written:
            time.sleep(0.01)
        self.assertEqual(device.written, [event])

        device.push(bytes((0xFF, 0x00, 0x03, 0x00)))
        command = bytes((0x01, 0x01, 0x10, 0x00))
        device.push(command)
        phone.settimeout(2)
        received = phone.recv(64)
        self.assertEqual(received, command)
        self.assertEqual(indexes, [3])

        bridge.close()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        phone.close()


class HandshakeTest(unittest.TestCase):
    def test_open_phone_requires_the_code(self):
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]

        def serve(reply: bytes) -> bytes:
            conn, _ = server.accept()
            line = b""
            while not line.endswith(b"\n"):
                line += conn.recv(1)
            conn.sendall(reply)
            conn.close()
            return line

        good = threading.Thread(target=serve, args=(b"OK\n",), daemon=True)
        good.start()
        client = open_phone("127.0.0.1", port, "ABCD12", timeout=2)
        client.close()
        good.join(2)

        denied = threading.Thread(target=serve, args=(b"DENY\n",), daemon=True)
        denied.start()
        with self.assertRaises(LinkError):
            open_phone("127.0.0.1", port, "NOPE00", timeout=2)
        denied.join(2)
        server.close()


class BeaconTest(unittest.TestCase):
    def test_parse_beacon(self):
        phone = parse_beacon(b"BLUROOT\t1\t8877\twaiting\tPixel 8\n", "192.168.1.9")
        assert phone is not None
        self.assertEqual(phone.port, 8877)
        self.assertEqual(phone.state, "waiting")
        self.assertEqual(phone.name, "Pixel 8")
        self.assertIsNone(parse_beacon(b"hello", "127.0.0.1"))


class ControllerOwnershipTest(unittest.TestCase):
    def test_idle_bridge_does_not_inject_commands_after_startup(self):
        phone, pc = socket.socketpair()
        device = FakeDevice()
        bridge = Bridge(pc, device)
        thread = threading.Thread(target=bridge.run, daemon=True)
        thread.start()
        try:
            # The former startup sequence injected commands after 1.5 seconds.
            phone.settimeout(2)
            with self.assertRaises(socket.timeout):
                phone.recv(256)
            self.assertTrue(thread.is_alive())
            self.assertEqual(bridge.to_phone, 0)
        finally:
            bridge.close()
            thread.join(2)
            phone.close()
        self.assertFalse(thread.is_alive())

    def test_phone_disconnect_closes_adapter_and_reports_reason(self):
        phone, pc = socket.socketpair()
        device = FakeDevice()
        logs: list[str] = []
        bridge = Bridge(pc, device, on_log=logs.append)
        thread = threading.Thread(target=bridge.run, daemon=True)
        thread.start()
        phone.close()
        thread.join(2)
        try:
            self.assertFalse(thread.is_alive())
            self.assertTrue(device.closed)
            self.assertIn("Phone link ended: the phone closed the connection", logs)
        finally:
            bridge.close()


if __name__ == "__main__":
    unittest.main()
