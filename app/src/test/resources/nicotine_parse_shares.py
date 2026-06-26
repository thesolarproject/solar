#!/usr/bin/env python3
"""Parse a zlib PEER_SHARES_REPLY body with Nicotine+ and print file count (exit 0 if >0)."""
import sys

from pynicotine.slskmessages import SharedFileListResponse

if len(sys.argv) != 2:
    sys.stderr.write("usage: nicotine_parse_shares.py <zlib-bytes-file>\n")
    sys.exit(2)

data = open(sys.argv[1], "rb").read()
msg = SharedFileListResponse()
msg.parse_network_message(data)
total = sum(len(files) for _dir, files in msg.list)
print(total)
sys.exit(0 if total > 0 else 1)
