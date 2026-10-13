import argparse
import os
import sys

from bluroot.link import LinkError, Session


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="bluroot",
        description="Use a rooted Android phone as this PC's Bluetooth radio.",
    )
    parser.add_argument("--usb", action="store_true", help="connect through adb")
    parser.add_argument("--host", help="phone IP address for Wi-Fi")
    parser.add_argument("--port", type=int, default=8877)
    parser.add_argument("--code", default="", help="code shown on the phone")
    parser.add_argument("--serial", help="adb serial when more than one phone is plugged in")
    args = parser.parse_args(argv)

    if not args.usb and not args.host:
        if not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
            parser.error("no graphical session is available; pass --usb or --host")
        from bluroot.gui import run_gui

        run_gui()
        return 0
    return _cli(args)


def _cli(args: argparse.Namespace) -> int:
    done = __import__("threading").Event()

    def on_event(kind: str, payload) -> None:
        if kind == "log":
            print(payload, flush=True)
        elif kind == "status":
            print(payload, flush=True)
        elif kind == "index":
            print(f"Adapter hci{payload} is ready", flush=True)
        elif kind == "counts":
            to_phone, to_pc = payload
            print(f"to phone {to_phone}  to pc {to_pc}", flush=True)
        elif kind == "stopped":
            done.set()

    session = Session(on_event)
    try:
        if args.usb:
            session.start_usb(args.serial, args.port, args.code)
        else:
            session.start_wifi(args.host, args.port, args.code)
    except LinkError as error:
        print(error, file=sys.stderr)
        return 1

    try:
        while not done.wait(0.5):
            pass
    except KeyboardInterrupt:
        print("\nStopping", flush=True)
        session.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
