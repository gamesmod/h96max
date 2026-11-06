#!/bin/bash
# Serve release/updates to the box on the LAN: http://<this PC>:8099/ (default source in /vendor/etc/tvbox/update-urls.txt)
R=$(cd "$(dirname "$0")/.." && pwd)
exec python3 -I -m http.server 8099 --bind 0.0.0.0 --directory $R/release/updates
