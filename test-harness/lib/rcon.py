#!/usr/bin/env python3
"""Minimal RCON (Source protocol) client.

Usage:
    python3 rcon.py <host> <port> <password> "<command>"

Exit codes:
    0  success (command sent + response received)
    1  argv error
    2  authentication failed
    3  no response / truncated packet
    4  connect / socket error

Response body is written to stdout. Diagnostics go to stderr.
Stdlib only.
"""

from __future__ import annotations

import socket
import struct
import sys


SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0


def _pack(req_id: int, ptype: int, body: str) -> bytes:
    encoded = body.encode("utf-8")
    payload = struct.pack("<ii", req_id, ptype) + encoded + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    out = b""
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            return out
        out += chunk
    return out


def _recv_packet(sock: socket.socket):
    raw_len = _recv_exact(sock, 4)
    if len(raw_len) < 4:
        return None
    (length,) = struct.unpack("<i", raw_len)
    if length < 10 or length > 4_500_000:
        return None
    data = _recv_exact(sock, length)
    if len(data) < length:
        return None
    req_id, ptype = struct.unpack("<ii", data[:8])
    body = data[8:-2]  # trailing \x00\x00
    return req_id, ptype, body.decode("utf-8", errors="replace")


def rcon_exec(host: str, port: int, password: str, command: str, timeout: float = 10.0) -> str:
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except OSError as exc:
        print(f"RCON_CONNECT_FAIL: {exc}", file=sys.stderr)
        sys.exit(4)

    try:
        sock.sendall(_pack(1, SERVERDATA_AUTH, password))
        pkt = _recv_packet(sock)
        if pkt is None:
            print("RCON_AUTH_NO_RESPONSE", file=sys.stderr)
            sys.exit(3)
        if pkt[0] == -1:
            print("RCON_AUTH_FAIL", file=sys.stderr)
            sys.exit(2)

        sock.sendall(_pack(2, SERVERDATA_EXECCOMMAND, command))
        pkt = _recv_packet(sock)
        if pkt is None:
            print("RCON_EXEC_NO_RESPONSE", file=sys.stderr)
            sys.exit(3)
        return pkt[2]
    finally:
        try:
            sock.close()
        except OSError:
            pass


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print("usage: rcon.py <host> <port> <password> <command>", file=sys.stderr)
        return 1
    host = argv[1]
    try:
        port = int(argv[2])
    except ValueError:
        print("port must be an integer", file=sys.stderr)
        return 1
    password = argv[3]
    command = argv[4]
    body = rcon_exec(host, port, password, command)
    sys.stdout.write(body)
    if not body.endswith("\n"):
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
