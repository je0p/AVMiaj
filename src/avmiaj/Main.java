package avmiaj;

import java.io.*;
import java.nio.file.*;

/**
 * Fully rootless Alpine-VM-in-a-jar.
 *
 * Does not require: root, --privileged, proot, or a pre-installed
 * qemu-system-x86_64. Only requires apt-get/apt-cache/dpkg-deb to be
 * present (no root needed to use them in "download only" mode).
 *
 * Steps:
 *  1. Resolves the full dependency closure of the qemu-system-x86 package
 *     via apt-cache depends --recurse.
 *  2. Downloads every .deb with apt-get download (writes to cwd, no root
 *     needed).
 *  3. Extracts every .deb with dpkg-deb -x into a local directory
 *     (again, no root needed — this does not touch /var/lib/dpkg).
 *  4. Builds an LD_LIBRARY_PATH from every directory in that local root
 *     that contains a .so file.
 *  5. Finds QEMU's dynamically loaded modules (especially accel-tcg-x86_64.so)
 *     and sets QEMU_MODULE_DIR accordingly.
 *  6. Downloads the latest Alpine Linux "virt" ISO (install mode only).
 *  7. Boots the VM from the ISO (install) or from the persistent disk
 *     (run) and automatically logs in as root on the serial console.
 */
public final class Main {

    public static void main(String[] args) throws Exception {

        // Validate the command line first: unknown options are an error.
        boolean installFlag = false;
        boolean runFlag = false;
        for (String arg : args) {
            switch (arg) {
                case "-h":
                case "--help":
                    printUsage(System.out);
                    return;
                case "--install":
                    installFlag = true;
                    break;
                case "--run":
                    runFlag = true;
                    break;
                default:
                    System.err.println("Unknown option: " + arg);
                    System.err.println();
                    printUsage(System.err);
                    System.exit(2);
            }
        }
        if (installFlag && runFlag) {
            System.err.println("--install and --run cannot be used together.");
            System.exit(2);
        }

        // Pick the mode before anything else (the flags skip the prompt).
        Config.loadConfig();
        boolean installMode;

        if (installFlag) {
            installMode = true;
        } else if (runFlag) {
            installMode = false;
        } else {
            installMode = Menu.askInstallMode();
        }

        QemuInstaller.requireTool("apt-cache");
        QemuInstaller.requireTool("apt-get");
        QemuInstaller.requireTool("dpkg-deb");

        Path qemuBin = QemuInstaller.findQemuBinary();

        if (qemuBin == null) {
            System.out.println(
                    "qemu-system-x86_64 not found locally - "
                    + "downloading packages (no root)..."
            );

            QemuInstaller.fetchAndExtractQemu();

            qemuBin = QemuInstaller.findQemuBinary();

            if (qemuBin == null) {
                throw new IOException(
                        "Could not find qemu-system-x86_64 "
                        + "after extracting the packages."
                );
            }
        } else {
            System.out.println(
                    "Found already extracted QEMU: " + qemuBin
            );
        }

        Files.createDirectories(Config.WORK);

        // Persistent 768 MiB virtual disk for Alpine Linux.
        if (!Files.exists(Config.DISK)) {
            try (RandomAccessFile disk = new RandomAccessFile(Config.DISK.toFile(), "rw")) {
                disk.setLength(Config.diskMb * 1024L * 1024);
            }
        }

        if (installMode) {
            if (!Files.exists(Config.ISO) || Files.size(Config.ISO) < 1024 * 1024) {
                AlpineIso.downloadLatestAlpineVirtIso();
            }
            System.out.println("Install mode: installing Alpine to disk: " + Config.DISK);
        } else {
            System.out.println("Run mode: booting Alpine from disk: " + Config.DISK);
        }

        String ldLibraryPath = QemuInstaller.buildLdLibraryPath();

        boolean kvm =
                Files.isReadable(Path.of("/dev/kvm"))
                && Files.isWritable(Path.of("/dev/kvm"));

        Vm.bootVm(qemuBin, ldLibraryPath, kvm, installMode);
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("AVMiaj - Alpine-VM-in-a-jar");
        out.println();
        out.println("Usage: java -jar avmiaj.jar [--install | --run]");
        out.println("       java -jar avmiaj.jar --help");
        out.println();
        out.println("Options:");
        out.println("  --install    Install Alpine to the virtual disk (first run)");
        out.println("  --run        Boot the installed system");
        out.println("  -h, --help   Show this help and exit");
        out.println();
        out.println("Without an option an interactive menu is shown");
        out.println("(boot / install / settings).");
        out.println("Settings (RAM, CPUs, disk, port forwards) are saved in:");
        out.println("  " + Config.CONFIG);
        out.println();
        out.println("Type 'stop' in the console to kill the VM and exit AVMiaj.");
    }
}
