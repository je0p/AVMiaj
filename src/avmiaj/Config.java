package avmiaj;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Paths and user settings. Settings are saved to {@code config.properties}
 * in the work directory and can be edited from the menu.
 */
final class Config {

    private Config() {
    }

    static final Path WORK = resolveWorkDir();

    private static Path resolveWorkDir() {
        String home = System.getProperty("user.home");

        if (home != null && !home.isBlank()) {
            Path candidate = Path.of(home, ".avmiaj");

            if (isWritableParent(Path.of(home))) {
                return candidate;
            }
        }

        return Path.of(
                System.getProperty("java.io.tmpdir", "/tmp"),
                "avmiaj"
        );
    }

    private static boolean isWritableParent(Path dir) {
        return Files.isWritable(dir) || !Files.exists(dir);
    }

    static final Path PKG_DIR = WORK.resolve("pkgs");

    static final Path ROOT_DIR = WORK.resolve("qemu-root");

    static final Path ISO = WORK.resolve("alpine-virt.iso");

    static final Path DISK = WORK.resolve("alpine-disk.img");

    // ---- Settings (persisted in WORK/config.properties, editable from the menu) ----
    static final Path CONFIG = WORK.resolve("config.properties");

    static int memMb = 512;

    static int cpus = 1;

    static int diskMb = 768;

    static final List<String> portForwards = new ArrayList<>();

    /** Returns "host:guest" or "host:guest/udp", or null if invalid. */
    static String normalizePortForward(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        boolean udp = false;
        if (s.endsWith("/udp")) {
            udp = true;
            s = s.substring(0, s.length() - 4);
        } else if (s.endsWith("/tcp")) {
            s = s.substring(0, s.length() - 4);
        }
        String[] parts = s.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int host = Integer.parseInt(parts[0].trim());
            int guest = Integer.parseInt(parts[1].trim());
            if (host < 1 || host > 65535 || guest < 1 || guest > 65535) {
                return null;
            }
            return host + ":" + guest + (udp ? "/udp" : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String buildNicArg() {
        StringBuilder sb = new StringBuilder("user,model=e1000");
        for (String pf : portForwards) {
            String proto = "tcp";
            String spec = pf;
            if (spec.endsWith("/udp")) {
                proto = "udp";
                spec = spec.substring(0, spec.length() - 4);
            }
            String[] hg = spec.split(":");
            sb.append(",hostfwd=").append(proto)
              .append("::").append(hg[0]).append("-:").append(hg[1]);
        }
        return sb.toString();
    }

    static void loadConfig() {
        if (!Files.isRegularFile(CONFIG)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("Could not read " + CONFIG + ": " + e.getMessage());
            return;
        }
        memMb = intSetting(props, "mem", memMb, 128, 524288);
        cpus = intSetting(props, "cpus", cpus, 1, 64);
        diskMb = intSetting(props, "disk", diskMb, 256, 1048576);
        portForwards.clear();
        for (String part : props.getProperty("ports", "").split(",")) {
            if (part.isBlank()) {
                continue;
            }
            String norm = normalizePortForward(part);
            if (norm != null) {
                portForwards.add(norm);
            } else {
                System.err.println("Ignoring invalid port forward in config: "
                        + part.trim());
            }
        }
    }

    private static int intSetting(Properties props, String key, int def,
                                  int min, int max) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        System.err.println("Ignoring invalid value for '" + key + "' in config: " + v);
        return def;
    }

    static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("mem", String.valueOf(memMb));
        props.setProperty("cpus", String.valueOf(cpus));
        props.setProperty("disk", String.valueOf(diskMb));
        props.setProperty("ports", String.join(",", portForwards));
        try {
            Files.createDirectories(WORK);
            try (OutputStream out = Files.newOutputStream(CONFIG)) {
                props.store(out, "AVMiaj settings (mem/disk in MB, "
                        + "ports as host:guest[/udp], comma-separated)");
            }
        } catch (IOException e) {
            System.err.println("Could not save settings: " + e.getMessage());
        }
    }
}
