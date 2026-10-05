package avmiaj;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Starts QEMU and connects its console to the host terminal (auto-login as
 * root, {@code stop} handling).
 */
final class Vm {

    private Vm() {
    }

    static void bootVm(
            Path qemuBin,
            String ldLibraryPath,
            boolean kvm,
            boolean installMode
    ) throws IOException, InterruptedException {

        /*
         * qemu-system-data ships BIOS/firmware under
         * usr/share/qemu.
         *
         * Because QEMU is not actually installed system-wide,
         * explicitly point it at the extracted directory.
         */
        Path shareQemu =
                Config.ROOT_DIR.resolve("usr/share/qemu");

        // QEMU searches its -L directory for VGA option ROMs.
        // Debian extracts these into usr/share/vgabios, so copy the
        // standard VGA ROM into the local QEMU firmware directory.
        Path vgaBios = Config.ROOT_DIR.resolve(
                "usr/share/vgabios/vgabios-stdvga.bin"
        );
        if (!Files.isRegularFile(vgaBios)) {
            throw new IOException(
                    "VGA BIOS not found: " + vgaBios
            );
        }
        Files.createDirectories(shareQemu);
        Files.copy(
                vgaBios,
                shareQemu.resolve("vgabios-stdvga.bin"),
                StandardCopyOption.REPLACE_EXISTING
        );

        // Locate the EFI network ROM wherever the Debian package places it,
        // then expose it in QEMU's -L firmware directory.
        Path efiNicRom;
        try (Stream<Path> paths = Files.walk(Config.ROOT_DIR)) {
            efiNicRom = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .equals("efi-e1000.rom"))
                    .findFirst()
                    .orElse(null);
        }
        if (efiNicRom == null) {
            throw new IOException(
                    "efi-e1000.rom not found (ipxe-qemu package)"
            );
        }
        Files.copy(
                efiNicRom,
                shareQemu.resolve("efi-e1000.rom"),
                StandardCopyOption.REPLACE_EXISTING
        );

        /*
         * Find dynamically loaded QEMU modules.
         *
         * This is the important fix for:
         *
         *   fatal: could not load module for type
         *   'tcg-accel-ops'
         */
        Path qemuModuleDir =
                QemuInstaller.findQemuModuleDir();

        System.out.println();

        System.out.println(
                "QEMU binary: " + qemuBin
        );

        System.out.println(
                "QEMU data dir: " + shareQemu
        );

        if (qemuModuleDir != null) {
            System.out.println(
                    "QEMU module dir: "
                    + qemuModuleDir
            );
        } else {
            System.out.println(
                    "WARNING: module not found: "
                    + "accel-tcg-x86_64.so!"
            );
        }

        System.out.println();

        System.out.println(
                "Booting VM"
                + (
                    kvm
                        ? " (KVM-accelerated)"
                        : " (software emulation / TCG - "
                          + "no /dev/kvm, no host privileges needed)"
                  )
        );

        System.out.println(
                "Type 'stop' to kill the VM and exit AVMiaj."
        );

        /*
         * Start with explicit TCG when KVM is unavailable.
         *
         * QEMU normally defaults to TCG, but making this explicit
         * gives us deterministic rootless behaviour.
         */
        List<String> cmd = new ArrayList<>(List.of(
                qemuBin.toString(),
                "-m", String.valueOf(Config.memMb),
                "-smp", String.valueOf(Config.cpus),
                "-nographic",
                "-L", shareQemu.toString(),
                "-drive", "file=" + Config.DISK + ",format=raw,if=virtio",
                "-nic", Config.buildNicArg()
        ));

        if (installMode) {
            // Boot Alpine virt ISO; install it to /dev/vda from the console.
            cmd.addAll(List.of("-cdrom", Config.ISO.toString(), "-boot", "order=d"));
        } else {
            // Boot installed Alpine from the persistent virtual disk.
            cmd.addAll(List.of("-boot", "order=c"));
        }

        // Point QEMU at SeaBIOS from the locally extracted package.
        Path bios = Config.ROOT_DIR.resolve("usr/share/seabios/bios-256k.bin");
        if (Files.isRegularFile(bios)) {
            cmd.add("-bios");
            cmd.add(bios.toString());
        } else {
            throw new IOException(
                    "SeaBIOS BIOS not found: " + bios
            );
        }

        if (kvm) {

            /*
             * Hardware acceleration available.
             */
            cmd.add(1, "-accel");
            cmd.add(2, "kvm");

        } else {

            /*
             * No /dev/kvm.
             *
             * Use software emulation explicitly.
             */
            cmd.add(1, "-accel");
            cmd.add(2, "tcg");
        }

        ProcessBuilder pb =
                new ProcessBuilder(cmd);

        /*
         * Shared libraries from the extracted Debian root.
         */
        if (ldLibraryPath != null
                && !ldLibraryPath.isBlank()) {

            pb.environment().put(
                    "LD_LIBRARY_PATH",
                    ldLibraryPath
            );
        }

        /*
         * This is the critical rootless-QEMU fix.
         *
         * Debian's QEMU contains TCG as a dynamically loaded
         * module. Since we did not install QEMU into /usr,
         * QEMU cannot use its normal compiled-in module path.
         */
        if (qemuModuleDir != null) {

            pb.environment().put(
                    "QEMU_MODULE_DIR",
                    qemuModuleDir.toString()
            );
        }

        /*
         * Helpful for diagnostics if QEMU still cannot load
         * a module.
         */
        pb.environment().put(
                "QEMU_CPU",
                "max"
        );

        /*
         * The VM console goes through Java (PIPE) so we can detect the
         * login banner and automatically type "root".
         */
        pb.redirectInput(
                ProcessBuilder.Redirect.PIPE
        );

        pb.redirectOutput(
                ProcessBuilder.Redirect.PIPE
        );

        pb.redirectError(
                ProcessBuilder.Redirect.INHERIT
        );

        System.out.println("VM: " + Config.memMb + " MB RAM, " + Config.cpus
                + " CPU(s), port forwards: "
                + (Config.portForwards.isEmpty() ? "none" : String.join(", ", Config.portForwards)));
        System.out.println(
                "Starting QEMU..."
        );

        Process p = pb.start();

        Thread outThread = pumpVmConsole(p);

        int exit = p.waitFor();

        outThread.join(2000);

        System.out.println(
                "QEMU exited (code "
                + exit
                + ")."
        );
    }

    private static final java.util.regex.Pattern KERNEL_BANNER =
            java.util.regex.Pattern.compile(
                    "Kernel\\s+\\S+\\s+on\\s+x86_64\\s+\\(/dev/ttyS0\\)"
            );

    private static void writeToVm(OutputStream vmIn, java.io.ByteArrayOutputStream held)
            throws IOException {
        synchronized (vmIn) {
            vmIn.write(held.toByteArray());
            vmIn.flush();
        }
        held.reset();
    }

    /** True while the typed line could still become "stop". */
    private static boolean couldBeStop(String line) {
        String t = line.stripLeading().toLowerCase(Locale.ROOT);
        if (t.length() <= 4) {
            return "stop".startsWith(t);
        }
        return t.startsWith("stop") && t.substring(4).isBlank();
    }

    /**
     * Pipes the QEMU console to/from the host terminal and, once the
     * "Kernel X on x86_64 (/dev/ttyS0)" banner is detected, types "root"
     * (once). A line saying "stop" kills QEMU. Returns the thread that
     * reads QEMU's output.
     */
    private static Thread pumpVmConsole(Process p) {

        final OutputStream vmIn = p.getOutputStream();
        final java.util.concurrent.atomic.AtomicBoolean rootSent =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        final Runnable sendRoot = () -> {
            if (rootSent.compareAndSet(false, true)) {
                try {
                    synchronized (vmIn) {
                        vmIn.write("root\n".getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII));
                        vmIn.flush();
                    }
                } catch (IOException ignored) {
                }
            }
        };

        // Host stdin -> QEMU stdin. A line that says "stop" is not forwarded:
        // it kills the VM and ends AVMiaj (panels send "stop" on their Stop
        // button). Bytes are held back only while the line could still turn
        // out to be "stop"; anything else is forwarded immediately.
        Thread inThread = new Thread(() -> {
            byte[] buf = new byte[1024];
            java.io.ByteArrayOutputStream held = new java.io.ByteArrayOutputStream();
            boolean passthrough = false;
            int n;
            try {
                while ((n = System.in.read(buf)) != -1) {
                    for (int i = 0; i < n; i++) {
                        held.write(buf[i]);
                        if (buf[i] == '\n') {
                            String line = held.toString(
                                    java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                            if (!passthrough && line.equalsIgnoreCase("stop")) {
                                System.out.println("Stopping AVMiaj: killing the VM...");
                                System.out.flush();
                                p.destroy();
                                try {
                                    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                                        p.destroyForcibly();
                                    }
                                } catch (InterruptedException e) {
                                    p.destroyForcibly();
                                }
                                return;
                            }
                            writeToVm(vmIn, held);
                            passthrough = false;
                        } else if (passthrough) {
                            writeToVm(vmIn, held);
                        } else if (!couldBeStop(held.toString(
                                java.nio.charset.StandardCharsets.ISO_8859_1))) {
                            passthrough = true;
                            writeToVm(vmIn, held);
                        }
                    }
                }
                if (held.size() > 0) {
                    writeToVm(vmIn, held);
                }
            } catch (IOException ignored) {
            }
        }, "vm-stdin");
        inThread.setDaemon(true);
        inThread.start();

        // Panels (e.g. Pterodactyl) only show a line after '\n', while prompts
        // (login:, Hostname [localhost], etc.) end without one.
        // When the VM output goes quiet mid-line for a moment, we push a '\n'.
        final java.util.concurrent.atomic.AtomicLong lastOutNanos =
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        final java.util.concurrent.atomic.AtomicBoolean partialLine =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread flushThread = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(50);
                    synchronized (System.out) {
                        if (partialLine.get()
                                && System.nanoTime() - lastOutNanos.get()
                                   > 150_000_000L) {
                            System.out.write('\n');
                            System.out.flush();
                            partialLine.set(false);
                        }
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "vm-line-flush");
        flushThread.setDaemon(true);
        flushThread.start();

        // QEMU stdout -> host stdout (+ login banner detection)
        Thread outThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            StringBuilder window = new StringBuilder();
            boolean bannerSeen = false;
            int n;

            try (InputStream is = p.getInputStream()) {
                while ((n = is.read(buf)) != -1) {
                    synchronized (System.out) {
                        System.out.write(buf, 0, n);
                        System.out.flush();
                        lastOutNanos.set(System.nanoTime());
                        partialLine.set(buf[n - 1] != '\n');
                    }

                    if (rootSent.get()) {
                        continue;
                    }

                    window.append(new String(
                            buf, 0, n,
                            java.nio.charset.StandardCharsets.ISO_8859_1));

                    if (window.length() > 2048) {
                        window.delete(0, window.length() - 2048);
                    }

                    if (!bannerSeen) {
                        java.util.regex.Matcher m =
                                KERNEL_BANNER.matcher(window);

                        if (m.find()) {
                            bannerSeen = true;
                            window.delete(0, m.end());

                            // Fallback: if "login:" never shows up,
                            // send root after 3 s.
                            Thread fallback = new Thread(() -> {
                                try {
                                    Thread.sleep(3000);
                                } catch (InterruptedException ignored) {
                                }
                                sendRoot.run();
                            }, "vm-autologin-fallback");
                            fallback.setDaemon(true);
                            fallback.start();
                        }
                    }

                    if (bannerSeen && window.indexOf("login:") >= 0) {
                        sendRoot.run();
                    }
                }
            } catch (IOException ignored) {
            }
        }, "vm-stdout");
        outThread.setDaemon(true);
        outThread.start();

        return outThread;
    }
}
