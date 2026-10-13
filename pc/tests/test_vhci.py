import os
import threading
import unittest

from bluroot.vhci import Vhci


class VhciCloseTest(unittest.TestCase):
    def setUp(self):
        reader, self.writer = os.pipe2(os.O_NONBLOCK)
        self.device = Vhci(reader)
        self.addCleanup(self.device.close)
        self.addCleanup(os.close, self.writer)

    def test_close_releases_an_idle_reader(self):
        started = threading.Event()
        ended = threading.Event()
        errors = []

        def read():
            started.set()
            try:
                self.device.read_packet()
            except EOFError:
                ended.set()
            except BaseException as error:
                errors.append(error)

        thread = threading.Thread(target=read, daemon=True)
        thread.start()
        self.assertTrue(started.wait(1))
        self.device.close()
        self.assertTrue(ended.wait(1), "closing must wake an idle adapter reader")
        thread.join(1)
        self.assertEqual(errors, [])
        self.assertFalse(thread.is_alive())

    def test_packet_is_read_after_becoming_available(self):
        result = []
        thread = threading.Thread(target=lambda: result.append(self.device.read_packet()), daemon=True)
        thread.start()
        os.write(self.writer, b"\x01\x03\x0c\x00")
        thread.join(1)
        self.assertFalse(thread.is_alive())
        self.assertEqual(result, [b"\x01\x03\x0c\x00"])

    def test_operations_after_close_fail_and_close_is_idempotent(self):
        self.device.close()
        self.device.close()
        with self.assertRaises(EOFError):
            self.device.read_packet()
        with self.assertRaises(OSError):
            self.device.write_packet(b"\x01\x03\x0c\x00")
