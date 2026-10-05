package avmiaj;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;

/**
 * Downloads the latest Alpine Linux "virt" ISO and verifies its SHA-256
 * checksum.
 */
final class AlpineIso {

    private AlpineIso() {
    }

    private static final String ALPINE_INDEX_URL =
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/";

    static void downloadLatestAlpineVirtIso()
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(ALPINE_INDEX_URL))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Could not fetch the Alpine index, HTTP " + resp.statusCode());
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("alpine-virt-([0-9.]+)-x86_64\\.iso").matcher(resp.body());
        String bestVersion = null;
        while (matcher.find()) {
            String version = matcher.group(1);
            if (bestVersion == null || compareVersions(version, bestVersion) > 0) bestVersion = version;
        }
        if (bestVersion == null) throw new IOException("No alpine-virt ISO found in the index.");
        String isoUrl = ALPINE_INDEX_URL + "alpine-virt-" + bestVersion + "-x86_64.iso";

        // Download to a .part file first, so a truncated or corrupt image
        // is never mistaken for a finished ISO.
        Path part = Config.ISO.resolveSibling(Config.ISO.getFileName() + ".part");
        download(isoUrl, part);
        try {
            verifyIsoChecksum(client, isoUrl + ".sha256", part);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, Config.ISO, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Checks the ISO against the .sha256 file Alpine publishes next to it.
     * A mismatch is fatal; an unreachable checksum file only warns.
     */
    private static void verifyIsoChecksum(HttpClient client, String checksumUrl, Path iso)
            throws IOException, InterruptedException {
        String expected = null;
        try {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(checksumUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^\\s*([0-9a-fA-F]{64})\\b").matcher(resp.body());
                if (m.find()) {
                    expected = m.group(1).toLowerCase(Locale.ROOT);
                }
            }
        } catch (IOException e) {
            // handled below as "checksum unavailable"
        }

        if (expected == null) {
            System.err.println("WARNING: could not get the SHA-256 checksum from "
                    + checksumUrl + " - skipping verification.");
            return;
        }

        System.out.println("Verifying SHA-256 of the ISO...");
        String actual = sha256(iso);
        if (!actual.equals(expected)) {
            throw new IOException("SHA-256 mismatch for the Alpine ISO (expected "
                    + expected + ", got " + actual + "). "
                    + "The file was deleted - run again to retry.");
        }
        System.out.println("SHA-256 OK.");
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.split("\\.");
        String[] bb = b.split("\\.");
        for (int i = 0; i < Math.max(aa.length, bb.length); i++) {
            int x = i < aa.length ? Integer.parseInt(aa[i]) : 0;
            int y = i < bb.length ? Integer.parseInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static void download(
            String url,
            Path dest
    ) throws IOException, InterruptedException {

        System.out.println(
                "Downloading " + url
        );

        HttpClient client =
                HttpClient.newBuilder()
                        .followRedirects(
                                HttpClient.Redirect.ALWAYS
                        )
                        .build();

        HttpRequest req =
                HttpRequest.newBuilder(
                                URI.create(url))
                        .GET()
                        .build();

        HttpResponse<Path> resp =
                client.send(
                        req,
                        HttpResponse.BodyHandlers.ofFile(dest)
                );

        if (resp.statusCode() / 100 != 2) {
            throw new IOException(
                    "Download failed, HTTP "
                    + resp.statusCode()
            );
        }

        System.out.println(
                "Saved to " + dest
                + " (" + Files.size(dest)
                + " bytes)"
        );
    }
}
