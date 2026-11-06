#!/usr/bin/env bash
# Installs or upgrades Geon as a systemd service (doc/deploy.md):
#
#   sudo deploy/install.sh path/to/geon
#
# The argument is the native executable, built with `./mvnw -Pnative -pl geon-app
# package` (geon-app/target/geon). Safe to run again: an existing
# /etc/geon/geon.env is kept, and the service is restarted if it was running.
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -x "$1" ]; then
	echo "Usage: sudo $0 path/to/geon (the native executable)" >&2
	exit 1
fi
if [ "$(id -u)" -ne 0 ]; then
	echo "$0 must run as root (sudo)." >&2
	exit 1
fi
binary=$1
here=$(cd "$(dirname "$0")" && pwd)

if ! id geon >/dev/null 2>&1; then
	useradd --system --home-dir /var/lib/geon --shell /usr/sbin/nologin geon
fi
install -d -o geon -g geon -m 750 /var/lib/geon
install -d -o root -g geon -m 750 /etc/geon
if [ ! -e /etc/geon/geon.env ]; then
	install -o root -g geon -m 640 "$here/geon.env" /etc/geon/geon.env
	echo "Wrote /etc/geon/geon.env with defaults; review it."
else
	echo "Kept /etc/geon/geon.env; compare it with $here/geon.env for new settings."
fi
install -o root -g root -m 755 "$binary" /usr/local/bin/geon
install -o root -g root -m 755 "$here/geon-import.sh" /usr/local/bin/geon-import
install -o root -g root -m 644 "$here/systemd/geon.service" /etc/systemd/system/geon.service
systemctl daemon-reload
systemctl enable geon.service >/dev/null
echo "Installed $(/usr/local/bin/geon version)."

if [ -e /var/lib/geon/geon.idx ]; then
	systemctl restart geon.service
	echo "Restarted geon.service."
else
	echo "No index yet. Import one, which also starts the service:"
	echo "  sudo geon-import photon-dump-COUNTRY-1.0-latest.jsonl.zst"
fi
