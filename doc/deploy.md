# Deploying Geon

Geon runs as one native executable under systemd, configured with environment
variables. There is no container image: a container would add footprint for nothing
the executable and systemd do not already give.

## Files

| Installed as | From | Purpose |
|---|---|---|
| `/usr/local/bin/geon` | `geon-app/target/geon` | The executable |
| `/usr/local/bin/geon-import` | `deploy/geon-import.sh` | Builds a new index and restarts Geon |
| `/etc/systemd/system/geon.service` | `deploy/systemd/geon.service` | The service, run as user `geon` |
| `/etc/geon/geon.env` | `deploy/geon.env` | Settings, mode 640 |
| `/var/lib/geon/geon.idx` | `geon import` | The index |

## Install or upgrade

```
./mvnw -Pnative -pl geon-app -am package
sudo deploy/install.sh geon-app/target/geon
sudo geon-import photon-dump-germany-1.0-latest.jsonl.zst
```

`install.sh` creates the `geon` user and the directories, keeps an existing
`/etc/geon/geon.env`, installs the files above and restarts the service if an index
exists. Dumps come from [GraphHopper](https://download1.graphhopper.com/public/).

## Configuration

Every `geon` option can be set in `/etc/geon/geon.env` as `GEON_` plus the option
name in upper case, with `-` written as `_`: `-listen-port` is `GEON_LISTEN_PORT`.
`geon --help` lists the options; `deploy/geon.env` lists the useful ones with their
defaults. A `GEON_` variable that matches no option is reported at startup, which
catches typos.

After changing settings: `sudo systemctl restart geon`.

## Ports

| Setting | Default | Serves |
|---|---|---|
| `GEON_LISTEN_IP`, `GEON_LISTEN_PORT` | `127.0.0.1:2322` | The Photon compatible API |
| `GEON_ADMIN_IP`, `GEON_ADMIN_PORT` | `127.0.0.1:2323` | `/health`, `/info`, `/metrics` |

Put the API behind a reverse proxy, or listen on `0.0.0.0` to serve it directly. Never
expose the admin port; scrape `/metrics` locally or through an SSH tunnel. Set
`GEON_ADMIN_PORT=none` to turn it off.

## Logging

Geon logs to standard output, which systemd sends to the journal. The default
`geon.env` formats lines for it: the journal adds the time and reads the level from a
`<4>` style prefix, and every line carries `geon.version` and `git.commit`:

```
journalctl -u geon -f
```

`GEON_LOG_X` sets the Rainbow Gum property `logging.X`, for example
`GEON_LOG_level=debug` or `GEON_LOG_level_io_netty=warning`.

## Imports

`geon-import` runs the import as the `geon` user with the same settings, writes the new
index next to the old one and moves it into place only when it is complete, then
restarts the service. A failed import leaves the old index serving.

Memory: the import keeps postings in memory up to `GEON_IMPORT_MEMORY` (default in
`geon.env`: 1 GB) and spills the rest to temporary files in the data directory. Germany
(27.6 million places) imports in a 3 GB JVM heap.

The native executable's import is several times slower than on a JVM, because its
garbage collector is the simple serial one. For large imports, run the jar on a JVM
instead:

```
java -Xmx4g -cp "geon-app-0.1.0-SNAPSHOT.jar:lib/*" ai.snaphop.geon.app.Main import -import-file -
```
