package avmiaj;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Interactive console menus (boot / install / settings) and the {@code stop}
 * command, which ends AVMiaj.
 */
final class Menu {

    private Menu() {
    }

    /**
     * Asks what to do. Reads stdin byte by byte (unbuffered) so it
     * doesn't swallow input that should later reach the QEMU console.
     * On EOF on stdin it defaults to booting the installed system.
     * Option 3 opens the settings menu (RAM, CPUs, disk, port forwards).
     */
    static boolean askInstallMode() throws IOException {
        while (true) {
            System.out.println();
            System.out.println("What do you want to do?");
            System.out.println("  [1] Boot the system");
            System.out.println("  [2] Install the system");
            System.out.println("  [3] Settings");
            System.out.print("> ");
            System.out.flush();

            String answer = readLine();
            if (answer == null) {
                System.out.println("No input - booting the system by default.");
                return false;
            }
            answer = answer.trim();

            if (answer.equals("1")) {
                return false;
            }
            if (answer.equals("2")) {
                return true;
            }
            if (answer.equals("3")) {
                settingsMenu();
                continue;
            }
            System.out.println("Enter 1, 2 or 3.");
        }
    }

    /** "stop" on any prompt ends AVMiaj (it is what panels send on Stop). */
    private static String stopCheck(String line) {
        if (line != null && line.trim().equalsIgnoreCase("stop")) {
            System.out.println("Stopping AVMiaj.");
            System.out.flush();
            System.exit(0);
        }
        return line;
    }

    /** Reads one line from stdin byte by byte; null on EOF with no data. */
    private static String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        int c;
        while ((c = System.in.read()) != -1) {
            any = true;
            if (c == '\n') {
                return stopCheck(sb.toString());
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return any ? stopCheck(sb.toString()) : null;
    }

    private static void settingsMenu() throws IOException {
        while (true) {
            System.out.println();
            System.out.println("Settings (saved in " + Config.CONFIG + ")");
            System.out.println("  [1] RAM: " + Config.memMb + " MB");
            System.out.println("  [2] CPUs: " + Config.cpus);
            System.out.println("  [3] Disk size: " + Config.diskMb + " MB"
                    + (Files.exists(Config.DISK)
                    ? " (disk already exists - applies only to a new disk, delete "
                      + Config.DISK + " to recreate it)"
                    : ""));
            System.out.println("  [4] Port forwards: "
                    + (Config.portForwards.isEmpty() ? "none" : String.join(", ", Config.portForwards)));
            System.out.println("  [0] Back");
            System.out.print("> ");
            System.out.flush();

            String answer = readLine();
            if (answer == null) {
                return;
            }
            switch (answer.trim()) {
                case "0":
                    return;
                case "1":
                    Config.memMb = askInt("RAM in MB", Config.memMb, 128, 524288);
                    Config.saveConfig();
                    break;
                case "2":
                    Config.cpus = askInt("CPUs", Config.cpus, 1, 64);
                    Config.saveConfig();
                    break;
                case "3":
                    Config.diskMb = askInt("Disk size in MB", Config.diskMb, 256, 1048576);
                    Config.saveConfig();
                    break;
                case "4":
                    askPortForwards();
                    Config.saveConfig();
                    break;
                default:
                    System.out.println("Enter 0-4.");
            }
        }
    }

    private static int askInt(String label, int current, int min, int max)
            throws IOException {
        System.out.print(label + " [" + min + "-" + max + ", current "
                + current + ", empty = keep]: ");
        System.out.flush();
        String line = readLine();
        if (line == null || line.isBlank()) {
            return current;
        }
        try {
            int n = Integer.parseInt(line.trim());
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        System.out.println("Invalid value, keeping " + current + ".");
        return current;
    }

    private static void askPortForwards() throws IOException {
        System.out.println("Enter port forwards as host:guest, comma-separated "
                + "(add /udp for UDP),");
        System.out.println("e.g. 25565:25565,2222:22 - an empty line clears the list.");
        System.out.println("Use host ports allocated to your server; ports below "
                + "1024 usually need root.");
        System.out.print("> ");
        System.out.flush();
        String line = readLine();
        if (line == null) {
            return;
        }
        List<String> parsed = new ArrayList<>();
        for (String part : line.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            String norm = Config.normalizePortForward(part);
            if (norm == null) {
                System.out.println("Invalid entry '" + part.trim() + "' - nothing changed.");
                return;
            }
            parsed.add(norm);
        }
        Config.portForwards.clear();
        Config.portForwards.addAll(parsed);
    }
}
