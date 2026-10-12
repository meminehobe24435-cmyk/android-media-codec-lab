import os
import struct
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXE = Path(os.environ.get("WAVE_CLI", str(ROOT / "build/wave_cli.exe")))


def chunk(kind, data):
    return kind + struct.pack("<I", len(data)) + data + (b"\0" if len(data) % 2 else b"")


def riff(data):
    return b"RIFF" + struct.pack("<I", len(data) + 4) + b"WAVE" + data


def fmt(code=1, channels=1, rate=48000, byte_rate=96000, align=2, bits=16):
    return chunk(b"fmt ", struct.pack("<HHIIHH", code, channels, rate, byte_rate, align, bits))


class WaveTests(unittest.TestCase):
    def check(self, data, valid):
        with tempfile.TemporaryDirectory(prefix="media_wave_") as folder:
            path = Path(folder) / "input.wav"
            path.write_bytes(data)
            run = subprocess.run([str(EXE), str(path)], capture_output=True, timeout=5)
            self.assertEqual(run.returncode, 0 if valid else 1, run.stderr.decode(errors="replace"))
            return run.stdout.decode().strip()

    def test_python_wave_oracle(self):
        for channels in (1, 2):
            for rate in (8000, 44100, 48000, 96000):
                with self.subTest(rate=rate, channels=channels), tempfile.TemporaryDirectory() as folder:
                    path = Path(folder) / "oracle.wav"
                    with wave.open(str(path), "wb") as w:
                        w.setnchannels(channels); w.setsampwidth(2); w.setframerate(rate)
                        w.writeframes(b"\0" * 20 * channels)
                    self.assertEqual(self.check(path.read_bytes(), True), f"{rate} {channels} 44 {20*channels}")

    def test_unknown_odd_chunk(self):
        data = riff(chunk(b"JUNK", b"x") + fmt() + chunk(b"data", b"\0\0"))
        self.assertEqual(self.check(data, True), "48000 1 54 2")

    def test_data_before_fmt(self):
        self.check(riff(chunk(b"data", b"\0\0") + fmt()), True)

    def test_truncations(self):
        data = riff(fmt() + chunk(b"data", b"\0\0"))
        for end in range(len(data)):
            with self.subTest(end=end): self.check(data[:end], False)

    def test_wrong_magic(self): self.check(b"RIFX" + riff(b"")[4:], False)
    def test_wrong_form(self): self.check(b"RIFF\x04\0\0\0AVI ", False)
    def test_extra_bytes(self): self.check(riff(fmt()+chunk(b"data",b"\0\0"))+b"x",False)
    def test_missing_fmt(self): self.check(riff(chunk(b"data",b"\0\0")),False)
    def test_missing_data(self): self.check(riff(fmt()),False)
    def test_empty_data(self): self.check(riff(fmt()+chunk(b"data",b"")),False)
    def test_duplicate_fmt(self): self.check(riff(fmt()+fmt()+chunk(b"data",b"\0\0")),False)
    def test_duplicate_data(self): self.check(riff(fmt()+chunk(b"data",b"\0\0")*2),False)
    def test_float_format(self): self.check(riff(fmt(code=3)+chunk(b"data",b"\0\0")),False)
    def test_24bit(self): self.check(riff(fmt(bits=24)+chunk(b"data",b"\0\0")),False)
    def test_zero_channels(self): self.check(riff(fmt(channels=0)+chunk(b"data",b"\0\0")),False)
    def test_many_channels(self): self.check(riff(fmt(channels=6)+chunk(b"data",b"\0\0")),False)
    def test_invalid_rate(self): self.check(riff(fmt(rate=0)+chunk(b"data",b"\0\0")),False)
    def test_invalid_byte_rate(self): self.check(riff(fmt(byte_rate=1)+chunk(b"data",b"\0\0")),False)
    def test_invalid_align(self): self.check(riff(fmt(align=1)+chunk(b"data",b"\0\0")),False)
    def test_unaligned_data(self): self.check(riff(fmt()+chunk(b"data",b"x")),False)
    def test_chunk_overflow(self): self.check(riff(fmt()+b"data\xff\xff\xff\xff"),False)
    def test_missing_padding(self): self.check(riff(fmt()+b"JUNK\x01\0\0\0x"),False)
    def test_short_chunk_header(self): self.check(riff(fmt()+b"x"),False)
    def test_extended_fmt_rejected(self):
        extended=struct.pack("<HHIIHHH",1,1,48000,96000,2,16,0)
        self.check(riff(chunk(b"fmt ",extended)+chunk(b"data",b"\0\0")),False)


if __name__ == "__main__": unittest.main()
