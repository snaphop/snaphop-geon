#!/usr/bin/env bash
# Builds a new index from a Photon dump and restarts Geon on it (doc/deploy.md):
#
#   sudo geon-import photon-dump-andorra-1.0-latest.jsonl.zst
#
# Accepts .jsonl, .jsonl.gz and .jsonl.zst (needs zstd). The import runs as the geon
# user with the settings in /etc/geon/geon.env. The new index replaces the old one
# only when it is complete, so a failed import leaves Geon serving the old data.
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -r "$1" ]; then
	echo "Usage: sudo $0 DUMP (.jsonl, .jsonl.gz or .jsonl.zst)" >&2
	exit 1
fi
dump=$(realpath "$1")
env_file=/etc/geon/geon.env

run_import() {
	# set -a exports every variable the env file sets, for geon to read.
	sudo -u geon bash -c 'set -a; . "$0"; set +a; exec /usr/local/bin/geon import -import-file -' "$env_file"
}

case "$dump" in
	*.zst) zstd -dc "$dump" | run_import ;;
	*.gz) gzip -dc "$dump" | run_import ;;
	*) run_import < "$dump" ;;
esac
systemctl restart geon.service
echo "Geon restarted on the new index."
