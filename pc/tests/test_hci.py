import unittest

from bluroot.hci import ACL, COMMAND, EVENT, ISO, H4Parser, HciError, controller_index


def _feed(parser: H4Parser, data: bytes) -> list[bytes]:
    return parser.feed(data)


class H4ParserTest(unittest.TestCase):
    def test_event_round_trip(self):
        frame = bytes((0x04, 0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00))
        self.assertEqual(_feed(H4Parser(), frame), [frame])

    def test_empty_event(self):
        frame = bytes((0x04, 0x0E, 0x00))
        self.assertEqual(_feed(H4Parser(), frame), [frame])

    def test_split_acl(self):
        parser = H4Parser()
        self.assertEqual(parser.feed(bytes((ACL, 0x00))), [])
        frames = parser.feed(bytes((0x00, 0x02, 0x00, 0xAA, 0xBB)))
        self.assertEqual(frames, [bytes((ACL, 0x00, 0x00, 0x02, 0x00, 0xAA, 0xBB))])

    def test_two_packets(self):
        data = bytes((COMMAND, 0x01, 0x10, 0x00, EVENT, 0x0E, 0x01, 0x00))
        frames = H4Parser().feed(data)
        self.assertEqual(len(frames), 2)
        self.assertEqual(frames[0][0], COMMAND)
        self.assertEqual(frames[1][0], EVENT)

    def test_iso_ignores_flag_bits(self):
        frame = bytes((ISO, 0x00, 0x00, 0x01, 0xC0, 0x7E))
        self.assertEqual(H4Parser().feed(frame), [frame])

    def test_bad_type(self):
        with self.assertRaises(HciError):
            H4Parser().feed(bytes((0x00,)))

    def test_controller_index_is_little_endian(self):
        self.assertEqual(controller_index(bytes((0xFF, 0x00, 0x02, 0x00))), 2)
        self.assertIsNone(controller_index(bytes((EVENT, 0x0E, 0x00))))


if __name__ == "__main__":
    unittest.main()
