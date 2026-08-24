#!/usr/bin/env python3

import argparse
import socket


def receive_reply(connection: socket.socket) -> str:
    chunks = bytearray()
    while b"OK\r\n" not in chunks and b"KO:" not in chunks:
        data = connection.recv(4096)
        if not data:
            break
        chunks.extend(data)
    reply = chunks.decode("utf-8", errors="replace")
    reply_lines = reply.splitlines()
    if any(line.startswith("KO:") for line in reply_lines) or "OK" not in reply_lines:
        raise RuntimeError(f"Android Emulator console rejected the command: {reply.strip()}")
    return reply


def send_command(connection: socket.socket, command: str) -> None:
    connection.sendall(f"{command}\r\n".encode("ascii"))
    receive_reply(connection)


def main() -> None:
    parser = argparse.ArgumentParser(description="Set a private Android Emulator console GPS fix")
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--latitude", type=float, required=True)
    parser.add_argument("--longitude", type=float, required=True)
    arguments = parser.parse_args()

    if not -90.0 <= arguments.latitude <= 90.0:
        parser.error("latitude is outside -90..90")
    if not -180.0 <= arguments.longitude <= 180.0:
        parser.error("longitude is outside -180..180")
    with open(arguments.token_file, encoding="ascii") as token_file:
        token = token_file.read().strip()
    if not token:
        raise RuntimeError("Android Emulator console token is empty")

    with socket.create_connection(("127.0.0.1", 5556), timeout=5) as connection:
        greeting = receive_reply(connection)
        if "Authentication required" in greeting:
            send_command(connection, f"auth {token}")
        send_command(connection, f"geo fix {arguments.longitude} {arguments.latitude}")
        connection.sendall(b"quit\r\n")


if __name__ == "__main__":
    main()
