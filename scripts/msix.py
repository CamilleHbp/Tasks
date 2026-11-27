#!/usr/bin/env python3
"""Pack an MSIX without the Windows SDK, equivalent to `makeappx pack /nc`.

An MSIX is a ZIP whose payload is listed with per-64 KiB SHA-256 hashes in AppxBlockMap.xml
and typed by [Content_Types].xml. Payload files are stored uncompressed, so blocks need no
compressed sizes. Signing (AppxSignature.p7x) is left to signtool or osslsigncode.
"""
import base64
import hashlib
import pathlib
import struct
import sys
import zipfile
import zlib
from xml.sax.saxutils import quoteattr

BLOCK = 64 * 1024
FOOTPRINT = {"AppxBlockMap.xml", "[Content_Types].xml", "AppxSignature.p7x"}
TYPES = {"dll": "application/x-msdownload", "exe": "application/x-msdownload", "png": "image/png",
         "xml": "text/xml", "json": "application/json", "winmd": "application/octet-stream",
         "ico": "image/x-icon", "txt": "text/plain", "pri": "application/octet-stream"}
DOS_TIME, DOS_DATE = 0, (2026 - 1980) << 9 | 1 << 5 | 1


def _parts(layout):
    files = sorted((p for p in layout.rglob("*") if p.is_file()), key=lambda p: p.relative_to(layout).as_posix().lower())
    names = [p.relative_to(layout).as_posix() for p in files]
    if "AppxManifest.xml" not in names:
        raise ValueError("layout has no AppxManifest.xml")
    lowered = [n.lower() for n in names]
    if len(set(lowered)) != len(lowered):
        raise ValueError("layout has names differing only by case")
    for name in names:
        if name in FOOTPRINT or not name.isascii() or any(c in name for c in ' %#?"<>|*:\\'):
            raise ValueError(f"unsupported package file name: {name}")
        if "." not in name.rsplit("/", 1)[-1]:
            raise ValueError(f"package file needs an extension: {name}")
    # The manifest goes last among payload parts, as makeappx orders it.
    order = sorted(range(len(names)), key=lambda i: names[i] == "AppxManifest.xml")
    return [(names[i], files[i]) for i in order]


def _block_map(entries):
    lines = ['<?xml version="1.0" encoding="UTF-8" standalone="no"?>',
             '<BlockMap xmlns="http://schemas.microsoft.com/appx/2010/blockmap" '
             'HashMethod="http://www.w3.org/2001/04/xmlenc#sha256">']
    for name, data in entries:
        lfh = 30 + len(name.encode())
        blocks = "".join(f'<Block Hash="{base64.b64encode(hashlib.sha256(data[i:i + BLOCK]).digest()).decode()}"/>'
                         for i in range(0, len(data), BLOCK))
        lines.append(f'<File Name={quoteattr(name.replace("/", chr(92)))} Size="{len(data)}" LfhSize="{lfh}">{blocks}</File>')
    lines.append("</BlockMap>")
    return "\r\n".join(lines).encode()


def _content_types(names):
    defaults = sorted({n.rsplit(".", 1)[-1].lower() for n in names})
    body = "".join(f'<Default Extension={quoteattr(ext)} ContentType={quoteattr(TYPES.get(ext, "application/octet-stream"))}/>'
                   for ext in defaults)
    body += ('<Override PartName="/AppxManifest.xml" ContentType="application/vnd.ms-appx.manifest+xml"/>'
             '<Override PartName="/AppxBlockMap.xml" ContentType="application/vnd.ms-appx.blockmap+xml"/>')
    return ('<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            + body + "</Types>").encode()


def _deflate(data):
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    return compressor.compress(data) + compressor.flush()


def pack(layout, output):
    layout, output = pathlib.Path(layout), pathlib.Path(output)
    entries = [(name, path.read_bytes()) for name, path in _parts(layout)]
    entries.append(("AppxBlockMap.xml", _block_map(entries)))
    entries.append(("[Content_Types].xml", _content_types([n for n, _ in entries])))
    central = bytearray()
    with output.open("wb") as out:
        for name, data in entries:
            raw = name.encode()
            offset = out.tell()
            crc = zipfile.crc32(data)
            if len(data) >= 0xFFFFFFFF or offset >= 0xFFFFFFFF:
                raise ValueError("package exceeds 4 GiB")
            # Footprint files are deflated, as makeappx writes them; signers rewrite them assuming so.
            method, stored = (8, _deflate(data)) if name in FOOTPRINT else (0, data)
            out.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0, method, DOS_TIME, DOS_DATE, crc, len(stored), len(data), len(raw), 0))
            out.write(raw)
            out.write(stored)
            central += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 45, 20, 0, method, DOS_TIME, DOS_DATE, crc, len(stored),
                                   len(data), len(raw), 0, 0, 0, 0, 0, offset) + raw
        start = out.tell()
        out.write(central)
        zip64 = out.tell()
        count = len(entries)
        out.write(struct.pack("<IQHHIIQQQQ", 0x06064B50, 44, 45, 45, 0, 0, count, count, len(central), start))
        out.write(struct.pack("<IIQI", 0x07064B50, 0, zip64, 1))
        out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, count, count, len(central), start, 0))
    return output


def verify(package):
    """Check every payload part against the block map and the content types, as Windows does on install."""
    import xml.etree.ElementTree as ET
    with zipfile.ZipFile(package) as archive:
        infos = {info.filename: info for info in archive.infolist()}
        ns = {"b": "http://schemas.microsoft.com/appx/2010/blockmap"}
        listed = {}
        for element in ET.fromstring(archive.read("AppxBlockMap.xml")).findall("b:File", ns):
            name = element.get("Name").replace("\\", "/")
            data = archive.read(name)
            info = infos[name]
            if int(element.get("Size")) != len(data) or int(element.get("LfhSize")) != 30 + len(info.orig_filename.encode()) + len(info.extra):
                raise ValueError(f"block map size mismatch: {name}")
            hashes = [b.get("Hash") for b in element.findall("b:Block", ns)]
            expected = [base64.b64encode(hashlib.sha256(data[i:i + BLOCK]).digest()).decode() for i in range(0, len(data), BLOCK)]
            if hashes != expected:
                raise ValueError(f"block hash mismatch: {name}")
            listed[name] = True
        payload = set(infos) - FOOTPRINT
        if set(listed) != payload:
            raise ValueError(f"block map does not match payload: {sorted(payload ^ set(listed))[:5]}")
        types = ET.fromstring(archive.read("[Content_Types].xml"))
        ct = "{http://schemas.openxmlformats.org/package/2006/content-types}"
        defaults = {d.get("Extension").lower() for d in types.iter(ct + "Default")}
        overrides = {o.get("PartName").lower() for o in types.iter(ct + "Override")}
        for name in set(infos) - {"[Content_Types].xml"}:
            if "/" + name.lower() not in overrides and name.rsplit(".", 1)[-1].lower() not in defaults:
                raise ValueError(f"no content type for {name}")
        return len(payload)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "verify":
        print(f"verified {verify(sys.argv[2])} payload files")
    elif len(sys.argv) == 3:
        print(pack(sys.argv[1], sys.argv[2]))
    else:
        sys.exit("usage: msix.py <layout> <output.msix> | msix.py verify <package.msix>")
