#!/usr/bin/env python3
"""Send one JSON command to the QEMU relay control socket."""

import argparse
import json
import socket
import sys


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", default="/run/trackglance/relay.sock")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("status")
    button = subparsers.add_parser("button")
    button.add_argument("button", choices=("back", "up", "select", "down"))
    button.add_argument("--duration-ms", type=int, default=100)
    heart_rate = subparsers.add_parser("heart-rate")
    heart_rate.add_argument("bpm", type=int)
    heart_rate.add_argument("--quality", default="excellent")
    args = parser.parse_args()
    request = {"command": args.command.replace("-", "_")}
    if args.command == "button":
        request.update(button=args.button, duration_ms=args.duration_ms)
    elif args.command == "heart-rate":
        request.update(bpm=args.bpm, quality=args.quality)
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(10)
        client.connect(args.socket)
        client.sendall((json.dumps(request) + "\n").encode("utf-8"))
        response = json.loads(client.makefile(encoding="utf-8").readline())
    print(json.dumps(response, sort_keys=True))
    if not response.get("ok"):
        sys.exit(1)


if __name__ == "__main__":
    main()
