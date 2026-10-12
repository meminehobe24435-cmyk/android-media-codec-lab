"""Bounded ISO BMFF box walk, not a full MPEG-4 validator or AAC decoder."""
import struct
from pathlib import Path

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"dinf"}


def boxes(data, start=0, end=None, depth=0):
    if len(data) > 16 * 1024 * 1024 or depth > 12:
        raise ValueError("size/depth limit")
    end = len(data) if end is None else end
    if not 0 <= start <= end <= len(data):
        raise ValueError("invalid box boundary")
    result = []
    while start < end:
        if end - start < 8:
            raise ValueError("truncated box header")
        size, kind = struct.unpack_from(">I4s", data, start)
        header = 8
        if size == 1:
            if end - start < 16:
                raise ValueError("truncated large box")
            size = struct.unpack_from(">Q", data, start + 8)[0]
            header = 16
        elif size == 0:
            size = end - start
        if size < header or size > end - start:
            raise ValueError("invalid box size")
        payload = start + header
        name = kind.decode("ascii", errors="strict")
        row = {"type": name, "offset": start, "size": size, "children": []}
        if kind in CONTAINERS:
            row["children"] = boxes(data, payload, start + size, depth + 1)
        elif kind == b"stsd":
            if size - header < 8:
                raise ValueError("short stsd")
            count = struct.unpack_from(">I", data, payload + 4)[0]
            row["children"] = boxes(data, payload + 8, start + size, depth + 1)
            if len(row["children"]) != count:
                raise ValueError("stsd entry count mismatch")
        result.append(row)
        start += size
    return result


def types(rows):
    for row in rows:
        yield row["type"]
        yield from types(row["children"])


def inspect(path):
    path = Path(path)
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("file size limit")
    rows = boxes(path.read_bytes())
    kinds = set(types(rows))
    if not {"ftyp", "moov", "mdat", "mp4a", "stts", "stsz"} <= kinds:
        raise ValueError("required AAC/MP4 structures missing")
    return rows


if __name__ == "__main__":
    import json
    import sys
    print(json.dumps(inspect(sys.argv[1]), indent=2))
