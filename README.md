# AVMiaj

![Java 11+](https://img.shields.io/badge/Java-11%2B-orange?logo=openjdk&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Linux%20x86__64-blue?logo=linux&logoColor=white)
![Rootless](https://img.shields.io/badge/root-not%20required-brightgreen)
![QEMU](https://img.shields.io/badge/powered%20by-QEMU-ff6600?logo=qemu&logoColor=white)
![Alpine](https://img.shields.io/badge/guest-Alpine%20Linux-0D597F?logo=alpinelinux&logoColor=white)
[![License](https://img.shields.io/github/license/je0p/AVMiaj)](LICENSE)
[![Stars](https://img.shields.io/github/stars/je0p/AVMiaj?style=flat)](https://github.com/je0p/AVMiaj/stargazers)

**Alpine Linux VM in a single jar: no root, no `--privileged`, no pre-installed QEMU.**

*AVMiaj = **A**lpine-**VM**-**i**n-**a**-**j**ar*

> **TL;DR:** turn a Minecraft server into a VPS. Got a Java-only panel (Pterodactyl and friends)? Run this jar, get a root Alpine Linux shell.

AVMiaj downloads QEMU on its own (as `.deb` packages extracted locally), fetches the latest Alpine Linux ISO and boots a virtual machine right in your terminal. It works where you don't have admin rights: shared hosting, containers, game/server panels like Pterodactyl.

## Features

- Zero root: nothing is installed system-wide, `/var/lib/dpkg` is never touched
- Automatic QEMU download with all dependencies (`apt-get download` + `dpkg-deb -x`)
- Automatic download of the newest `alpine-virt` ISO, verified against Alpine's published SHA-256 checksum
- KVM acceleration when `/dev/kvm` is available, TCG software emulation otherwise
- Persistent virtual disk (768 MB by default): install once, boot whenever you want
- In-program settings menu: RAM, CPUs, disk size and port forwards, no startup flags needed (made for panels like Pterodactyl)
- Auto-login as `root` once the login prompt appears
- Console over stdin/stdout, panel-friendly (partial lines are flushed with a newline)
- User-mode (NAT) networking with optional port forwarding (TCP/UDP): the VM has internet access out of the box and can accept connections

## Requirements

- Linux x86_64 with Debian/Ubuntu tooling (`apt-get`, `apt-cache`, `dpkg-deb`). Root is **not** required
- Java 11+
- Internet access (your apt mirrors + `dl-cdn.alpinelinux.org`)
- About 1 GB of free disk space (QEMU packages + VM disk)

## Usage

> [!WARNING]
> Many game hosts forbid running anything other than the game in their ToS. Check your provider's rules before using AVMiaj on their panel, or you risk getting your server suspended or banned.

Build (needs a JDK 11+):

```bash
mkdir out
javac -d out $(find src -name '*.java')
jar cfe avmiaj.jar avmiaj.Main -C out .
```

Or use a prebuilt `avmiaj.jar` from the Releases page. Releases are built by GitHub Actions from the source in this repo, with a `.sha256` file next to the jar.

Handy for a panel's startup command: `java -jar avmiaj.jar`.

Run (shows a menu: boot / install / settings):

```bash
java -jar avmiaj.jar
```

Or skip the prompt with an option:

```bash
java -jar avmiaj.jar --install   # first run: install Alpine to the virtual disk
java -jar avmiaj.jar --run       # boot the installed system
java -jar avmiaj.jar --help      # show usage
```

| Option | Description |
|---|---|
| `--install` | install Alpine to the virtual disk (first run) |
| `--run` | boot the installed system |
| `-h`, `--help` | show usage and exit |

Unknown options and `--install` together with `--run` are rejected with an error (exit code 2).

### Settings

Can't change startup flags (e.g. in Pterodactyl)? Pick **Settings** in the menu instead. It works over the panel console:

| Option | Default | Notes |
|---|---|---|
| RAM | 512 MB | |
| CPUs | 1 | |
| Disk size | 768 MB | applies only when the disk is created; delete `alpine-disk.img` to recreate it |
| Port forwards | none | `host:guest`, comma-separated, `/udp` for UDP, e.g. `25565:25565,2222:22` |

Settings are saved to `config.properties` (see below), so you can also edit that file in your panel's file manager.

> [!NOTE]
> Port forwards must use host ports allocated to your server. Ports below 1024 usually need root.

### Typical workflow

1. `java -jar avmiaj.jar --install` downloads QEMU and the ISO, then boots the Alpine installer
2. In the VM console run `setup-alpine` and install to `/dev/vda`
3. Shut the VM down (`poweroff`)
4. From now on: `java -jar avmiaj.jar --run`

### Stopping

Type `stop` in the console: it kills the VM and exits AVMiaj. This is also what a panel's **Stop** button usually sends, so it works out of the box. `stop` also works at the menu and settings prompts.

For a clean shutdown of the guest, run `poweroff` inside the VM first.

## What it downloads

AVMiaj contacts only these, and nothing else:

- **QEMU and its dependencies**: `.deb` packages from the apt repositories already configured on the host (`apt-get download`; the system's package database is not touched)
- **Alpine Linux ISO**: from `https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/`, verified against the SHA-256 checksum published next to it

No telemetry. Everything is written inside the work directory (see below), nothing is installed system-wide. The only hard-coded URL in the code is the Alpine one (`AlpineIso.java`).

## Project layout

| File | Purpose |
|---|---|
| `src/avmiaj/Main.java` | entry point, command-line options |
| `src/avmiaj/Config.java` | paths and saved settings |
| `src/avmiaj/Menu.java` | interactive menu, settings, the `stop` command |
| `src/avmiaj/QemuInstaller.java` | downloads and extracts QEMU with apt (no root) |
| `src/avmiaj/AlpineIso.java` | downloads the Alpine ISO, verifies SHA-256 |
| `src/avmiaj/Vm.java` | starts QEMU, console, auto-login |

## File locations

By default in `~/.avmiaj/` (if your home directory is writable), otherwise in `avmiaj/` inside Java's temp directory (usually `/tmp`):

| Path | Contents |
|---|---|
| `pkgs/` | downloaded `.deb` packages |
| `qemu-root/` | extracted QEMU and its libraries |
| `alpine-virt.iso` | Alpine installation image |
| `alpine-disk.img` | the VM's virtual disk |
| `config.properties` | saved settings (RAM, CPUs, disk, port forwards) |

To start from scratch, delete that directory.

## How it works

1. `apt-cache depends --recurse` resolves the dependency closure of `qemu-system-x86`
2. `apt-get download` fetches the packages into a local directory (using its own apt index, not the system one)
3. `dpkg-deb -x` extracts them into `qemu-root/`
4. `LD_LIBRARY_PATH` is built from every directory containing `.so` files
5. `QEMU_MODULE_DIR` points to QEMU's dynamic modules (e.g. `accel-tcg-x86_64.so`)
6. The Alpine ISO is downloaded to a `.part` file, checked against its `.sha256` and only then moved into place
7. QEMU starts with your `-m` / `-smp` settings, `-nographic`, a virtio disk and an e1000 NIC with your `hostfwd` rules

## Limitations

- Many game hosts forbid running anything other than the game in their ToS. Check your provider's rules before using this on their panel
- x86_64 only, and the host needs apt/dpkg tools
- Without KVM the VM is slow (TCG emulation)
- User-mode networking: outgoing traffic works, incoming only for the ports you forward in Settings
- Disk size can't be changed after the disk is created (delete the image to recreate it)
