# AVMiaj

![Java 11+](https://img.shields.io/badge/Java-11%2B-orange?logo=openjdk&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Linux%20x86__64-blue?logo=linux&logoColor=white)
![Rootless](https://img.shields.io/badge/root-not%20required-brightgreen)
![QEMU](https://img.shields.io/badge/powered%20by-QEMU-ff6600?logo=qemu&logoColor=white)
![Alpine](https://img.shields.io/badge/guest-Alpine%20Linux-0D597F?logo=alpinelinux&logoColor=white)
[![License](https://img.shields.io/github/license/je0p/AVMiaj)](LICENSE)
[![Stars](https://img.shields.io/github/stars/je0p/AVMiaj?style=flat)](https://github.com/USER/AVMiaj/stargazers)

**Alpine Linux VM in a single Java file: no root, no `--privileged`, no pre-installed QEMU.**

*AVMiaj = **A**lpine-**VM**-**i**n-**a**-**j**ar*

> **TL;DR:** turn a Minecraft server into a VPS. Got a Java-only panel (Pterodactyl and friends)? Run this jar, get a root Alpine Linux shell.

AVMiaj downloads QEMU on its own (as `.deb` packages extracted locally), fetches the latest Alpine Linux ISO and boots a virtual machine right in your terminal. It works where you don't have admin rights: shared hosting, containers, game/server panels like Pterodactyl.

## Features

- Zero root: nothing is installed system-wide, `/var/lib/dpkg` is never touched
- Automatic QEMU download with all dependencies (`apt-get download` + `dpkg-deb -x`)
- Automatic download of the newest `alpine-virt` ISO
- KVM acceleration when `/dev/kvm` is available, TCG software emulation otherwise
- Persistent 768 MiB virtual disk: install once, boot whenever you want
- Auto-login as `root` once the login prompt appears
- Console over stdin/stdout, panel-friendly (partial lines are flushed with a newline)
- User-mode (NAT) networking: the VM has internet access out of the box

## Requirements

- Linux x86_64 with Debian/Ubuntu tooling (`apt-get`, `apt-cache`, `dpkg-deb`). Root is **not** required
- Java 11+
- Internet access (Debian mirrors + `dl-cdn.alpinelinux.org`)
- About 1 GB of free disk space (QEMU packages + VM disk)

## Usage

> [!WARNING]
> Many game hosts forbid running anything other than the game in their ToS. Check your provider's rules before using AVMiaj on their panel, or you risk getting your server suspended or banned.

Compile:

```bash
javac Main.java
```

Run (asks interactively what to do):

```bash
java Main
```

Or skip the prompt with a flag:

```bash
java Main --install   # first run: install Alpine to the virtual disk
java Main --run       # boot the installed system
```

### Typical workflow

1. `java Main --install` downloads QEMU and the ISO, then boots the Alpine installer
2. In the VM console run `setup-alpine` and install to `/dev/vda`
3. Shut the VM down (`poweroff`)
4. From now on: `java Main --run`

### Exiting the VM

Press `Ctrl+A`, then `X` (kills QEMU).

## File locations

By default in `~/.avmiaj/` (if your home directory is writable), otherwise in `$TMPDIR/avmiaj/`:

| Path | Contents |
|---|---|
| `pkgs/` | downloaded `.deb` packages |
| `qemu-root/` | extracted QEMU and its libraries |
| `alpine-virt.iso` | Alpine installation image |
| `alpine-disk.img` | the VM's virtual disk |

To start from scratch, delete that directory.

## How it works

1. `apt-cache depends --recurse` resolves the dependency closure of `qemu-system-x86`
2. `apt-get download` fetches the packages into a local directory (using its own apt index, not the system one)
3. `dpkg-deb -x` extracts them into `qemu-root/`
4. `LD_LIBRARY_PATH` is built from every directory containing `.so` files
5. `QEMU_MODULE_DIR` points to QEMU's dynamic modules (e.g. `accel-tcg-x86_64.so`)
6. QEMU starts with `-m 512`, `-nographic`, a virtio disk and an e1000 NIC

## Limitations

- x86_64 only, and the host needs apt/dpkg tools
- Without KVM the VM is slow (TCG emulation)
- User-mode networking: outgoing traffic works, incoming needs extra QEMU config (port forwarding)
- Defaults are 512 MB RAM and a 768 MiB disk; change them in the code (`-m`, `setLength`)
