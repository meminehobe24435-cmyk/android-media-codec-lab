import struct
import unittest
from tools.mp4_boxes import boxes, types

def box(kind, data=b""): return struct.pack(">I4s",len(data)+8,kind)+data

class BoxTests(unittest.TestCase):
    def test_empty(self): self.assertEqual(boxes(b""),[])
    def test_normal(self): self.assertEqual(boxes(box(b"ftyp"))[0]["size"],8)
    def test_nested(self): self.assertEqual(list(types(boxes(box(b"moov",box(b"trak"))))),["moov","trak"])
    def test_large(self): self.assertEqual(boxes(struct.pack(">I4sQ",1,b"mdat",16))[0]["size"],16)
    def test_to_end(self): self.assertEqual(boxes(struct.pack(">I4s",0,b"mdat")+b"abc")[0]["size"],11)
    def test_short(self):
        for size in range(1,8):
            with self.subTest(size=size), self.assertRaises(ValueError): boxes(b"x"*size)
    def test_small_size(self):
        with self.assertRaises(ValueError): boxes(struct.pack(">I4s",4,b"mdat"))
    def test_overflow(self):
        with self.assertRaises(ValueError): boxes(struct.pack(">I4s",0xffffffff,b"mdat"))
    def test_short_large(self):
        with self.assertRaises(ValueError): boxes(struct.pack(">I4s",1,b"mdat"))
    def test_boundary(self):
        with self.assertRaises(ValueError): boxes(b"",0,1)
    def test_depth(self):
        data=box(b"mdat")
        for _ in range(14): data=box(b"moov",data)
        with self.assertRaises(ValueError): boxes(data)
    def test_stsd(self):
        self.assertIn("mp4a",list(types(boxes(box(b"stsd",b"\0"*4+struct.pack(">I",1)+box(b"mp4a"))))))
    def test_stsd_count(self):
        with self.assertRaises(ValueError): boxes(box(b"stsd",b"\0"*8+box(b"mp4a")))
    def test_short_stsd(self):
        with self.assertRaises(ValueError): boxes(box(b"stsd",b"x"))

if __name__ == "__main__": unittest.main()
