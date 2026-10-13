#!/bin/bash
# Start the BluRoot window. Extra arguments are passed through, for example:
#   ./bluroot.sh --usb
cd "$(dirname "$(readlink -f "$0")")"
exec ./bluroot "$@"
