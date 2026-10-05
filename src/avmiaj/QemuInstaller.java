package avmiaj;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Downloads QEMU and its dependencies with apt (no root) and extracts them
 * into a local directory.
 */
final class QemuInstaller {

    private QemuInstaller() {
    }

    static void requireTool(String tool)
            throws IOException, InterruptedException {

        Process p = new ProcessBuilder(
                "sh",
                "-c",
                "command -v " + tool
        ).start();

        if (p.waitFor() != 0) {
            System.err.println(
                    "Missing tool `" + tool
                    + "` in PATH - without it QEMU "
                    + "cannot be downloaded without root."
            );

            System.exit(1);
        }
    }

    static Path findQemuBinary() throws IOException {

        if (!Files.isDirectory(Config.ROOT_DIR)) {
            return null;
        }

        try (Stream<Path> s = Files.walk(Config.ROOT_DIR)) {
            return s
                    .filter(p ->
                            p.getFileName()
                                    .toString()
                                    .equals("qemu-system-x86_64"))
                    .findFirst()
                    .orElse(null);
        }
    }

    static void fetchAndExtractQemu()
            throws IOException, InterruptedException {

        Files.createDirectories(Config.PKG_DIR);
        Files.createDirectories(Config.ROOT_DIR);

        /*
         * Don't touch the system's /var/lib/apt/lists.
         * Instead, use our own writable package index.
         */
        Path aptLists = Config.WORK.resolve("apt-lists");

        Files.createDirectories(
                aptLists.resolve("partial")
        );

        List<String> aptStateOpts = List.of(
                "-o",
                "Dir::State::lists=" + aptLists + "/",
                "-o",
                "Dir::State::lists::partial="
                        + aptLists + "/partial/",
                "-o",
                "Debug::NoLocking=1"
        );

        System.out.println(
                "Refreshing the apt index into a local, "
                + "writable directory (no root)..."
        );

        List<String> updateCmd =
                new ArrayList<>(List.of("apt-get"));

        updateCmd.addAll(aptStateOpts);
        updateCmd.add("update");

        runQuiet(updateCmd, null);

        /*
         * apt-get update may return non-zero because of an unrelated
         * repository, so check whether package indexes actually exist.
         */
        boolean freshIndex;

        try (DirectoryStream<Path> ds =
                     Files.newDirectoryStream(
                             aptLists,
                             "*Packages*")) {

            freshIndex = ds.iterator().hasNext();
        }

        System.out.println(
                freshIndex
                        ? "Index refreshed."
                        : "Could not refresh the index, "
                          + "will try the system one."
        );

        System.out.println(
                "Resolving dependencies of qemu-system-x86..."
        );

        List<String> pkgs =
                resolveDependencies(
                        "qemu-system-x86",
                        freshIndex ? aptStateOpts : List.<String>of()
                );

        // SeaBIOS provides the legacy PC BIOS firmware used by QEMU.
        if (!pkgs.contains("seabios")) {
            pkgs.add("seabios");
        }

        // VGA option ROMs are packaged separately from SeaBIOS.
        if (!pkgs.contains("vgabios")) {
            pkgs.add("vgabios");
        }
        // iPXE supplies additional network option ROMs used by QEMU.
        if (!pkgs.contains("ipxe-qemu")) {
            pkgs.add("ipxe-qemu");
        }

        System.out.println(
                "Packages to download: " + pkgs.size()
        );


        System.out.println(
                "Downloading .deb packages (apt-get download, "
                + "no root, one by one, with retry)..."
        );

        List<String> failed = new ArrayList<>();

        for (String pkg : pkgs) {

            boolean ok = false;

            for (int attempt = 1;
                 attempt <= 3 && !ok;
                 attempt++) {

                List<String> dlCmd =
                        new ArrayList<>(List.of("apt-get"));

                if (freshIndex) {
                    dlCmd.addAll(aptStateOpts);
                }

                dlCmd.addAll(
                        List.of("download", pkg)
                );

                int code = runQuiet(
                        dlCmd,
                        Config.PKG_DIR
                );

                ok = (code == 0);

                if (!ok && attempt < 3) {
                    System.out.println(
                            "  retry " + pkg
                            + " (" + attempt + "/3)..."
                    );
                }
            }

            if (!ok) {
                failed.add(pkg);
            }
        }

        if (!failed.isEmpty()) {
            throw new IOException(
                    "Failed to download: "
                    + failed
                    + " - even after refreshing "
                    + "the local apt index. "
                    + "Check your network / mirror availability "
                    + "and try again."
            );
        }

        System.out.println(
                "Extracting packages (dpkg-deb -x, "
                + "no root, no installation)..."
        );

        try (DirectoryStream<Path> debs =
                     Files.newDirectoryStream(
                             Config.PKG_DIR,
                             "*.deb")) {

            for (Path deb : debs) {

                run(
                        List.of(
                                "dpkg-deb",
                                "-x",
                                deb.toString(),
                                Config.ROOT_DIR.toString()
                        ),
                        null,
                        true
                );
            }
        }
    }

    private static List<String> resolveDependencies(
            String pkg, List<String> aptOpts)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of("apt-cache"));
        cmd.addAll(aptOpts);
        cmd.addAll(List.of(
                "depends",
                "--recurse",
                "--no-recommends",
                "--no-suggests",
                "--no-conflicts",
                "--no-breaks",
                "--no-replaces",
                "--no-enhances",
                pkg
        ));

        ProcessBuilder pb = new ProcessBuilder(cmd);

        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process p = pb.start();

        Set<String> names =
                new LinkedHashSet<>();

        try (BufferedReader r =
                     new BufferedReader(
                             new InputStreamReader(
                                     p.getInputStream()))) {

            String line;

            while ((line = r.readLine()) != null) {

                line = line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                /*
                 * Ignore dependency labels such as:
                 *
                 * Depends:
                 * PreDepends:
                 * Recommends:
                 *
                 * and virtual packages like:
                 * <some-virtual-package>
                 */
                if (line.matches("^\\|?[A-Za-z]+:.*")
                        || (line.startsWith("<")
                        && line.endsWith(">"))) {

                    continue;
                }

                names.add(line);
            }
        }

        p.waitFor();

        if (names.isEmpty()) {
            throw new IOException(
                    "apt-cache depends returned no "
                    + "packages - has `apt-get update` ever "
                    + "been run in this image?"
            );
        }

        return new ArrayList<>(names);
    }

    static String buildLdLibraryPath()
            throws IOException {

        try (Stream<Path> s =
                     Files.walk(Config.ROOT_DIR)) {

            Set<String> dirs =
                    s
                            .filter(p ->
                                    p.getFileName()
                                            .toString()
                                            .matches(
                                                    ".*\\.so(\\.[0-9a-zA-Z]+)*$"
                                            ))
                            .map(p ->
                                    p.getParent().toString())
                            .collect(
                                    Collectors.toCollection(
                                            LinkedHashSet::new
                                    )
                            );

            return String.join(":", dirs);
        }
    }

    /**
     * Finds QEMU's dynamic module directory.
     *
     * Debian Bookworm's qemu-system-common package contains
     * modules such as:
     *
     *   accel-tcg-x86_64.so
     *
     * under a directory similar to:
     *
     *   /usr/lib/x86_64-linux-gnu/qemu/
     *
     * After dpkg-deb -x this becomes:
     *
     *   ROOT_DIR/usr/lib/x86_64-linux-gnu/qemu/
     *
     * QEMU normally knows the system path, but our extracted
     * root is not installed into the real filesystem. Therefore
     * QEMU_MODULE_DIR must point at the extracted module directory.
     */
    static Path findQemuModuleDir()
            throws IOException {

        if (!Files.isDirectory(Config.ROOT_DIR)) {
            return null;
        }

        /*
         * Prefer the exact TCG accelerator module for x86_64.
         */
        try (Stream<Path> s =
                     Files.walk(Config.ROOT_DIR)) {

            Optional<Path> tcgModule =
                    s
                            .filter(Files::isRegularFile)
                            .filter(p ->
                                    p.getFileName()
                                            .toString()
                                            .equals(
                                                    "accel-tcg-x86_64.so"
                                            ))
                            .findFirst();

            if (tcgModule.isPresent()) {
                return tcgModule.get().getParent();
            }
        }

        /*
         * Fallback for a slightly different QEMU package layout.
         */
        try (Stream<Path> s =
                     Files.walk(Config.ROOT_DIR)) {

            Optional<Path> tcgModule =
                    s
                            .filter(Files::isRegularFile)
                            .filter(p -> {
                                String name =
                                        p.getFileName()
                                                .toString();

                                return name.startsWith("accel-tcg-")
                                        && name.endsWith(".so");
                            })
                            .findFirst();

            if (tcgModule.isPresent()) {
                return tcgModule.get().getParent();
            }
        }

        return null;
    }

    private static int runQuiet(
            List<String> cmd,
            Path dir
    ) throws IOException, InterruptedException {

        ProcessBuilder pb =
                new ProcessBuilder(cmd);

        if (dir != null) {
            pb.directory(dir.toFile());
        }

        pb.redirectOutput(
                ProcessBuilder.Redirect.DISCARD
        );

        pb.redirectError(
                ProcessBuilder.Redirect.DISCARD
        );

        Process p = pb.start();

        return p.waitFor();
    }

    private static void run(
            List<String> cmd,
            Path dir,
            boolean throwOnFail
    ) throws IOException, InterruptedException {

        ProcessBuilder pb =
                new ProcessBuilder(cmd);

        if (dir != null) {
            pb.directory(dir.toFile());
        }

        pb.redirectOutput(
                ProcessBuilder.Redirect.INHERIT
        );

        pb.redirectError(
                ProcessBuilder.Redirect.INHERIT
        );

        Process p = pb.start();

        int code = p.waitFor();

        if (code != 0 && throwOnFail) {
            throw new IOException(
                    "Command failed ("
                    + code
                    + "): "
                    + String.join(" ", cmd)
            );
        }
    }
}
