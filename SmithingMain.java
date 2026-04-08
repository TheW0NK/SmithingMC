import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class SmithingLauncher {

    private static final String VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String TARGET_VERSION   = "1.21";
    private static final Path   MINECRAFT_DIR    = Path.of(System.getProperty("user.home"), ".minecraft");
    private static final Path   LIBRARIES_DIR    = MINECRAFT_DIR.resolve("libraries");
    private static final Path   VERSIONS_DIR     = MINECRAFT_DIR.resolve("versions");

    public static void main(String[] args) throws Exception {
        printBanner();
        log("Smithing compiled for Minecraft 1.21.11.");
        log("Checking for Smithing License key...");
        // TODO: Make license code check
        log("Bypassed by Developer Mode.");
        log("
        ========= Welcome, {Username}. ========= \n
        You are on the Professional edition of Smithing by Bypassware. \n
        Your license key expires in: {Expiry Date}.\n
        This version is in developer mode, meaning you are testing. Godspeed, {Username}. \n
        ")
        log("Resolving version manifest...");
        String manifestJson = fetch(VERSION_MANIFEST);
        String versionUrl   = resolveVersionUrl(manifestJson);

        log("Fetching version JSON for " + TARGET_VERSION + "...");
        String versionJson  = fetch(versionUrl);
        JsonObject version  = JsonParser.parseString(versionJson).getAsJsonObject();

        log("Resolving libraries...");
        List<Path> classpath = resolveLibraries(version);

        log("Resolving client JAR...");
        Path clientJar = resolveClientJar(version);
        classpath.add(clientJar);

        log("Launching Minecraft " + TARGET_VERSION + "...");
        launch(classpath, args);
    }

    // ── Manifest ────────────────────────────────────────────────────────────────

    private static String resolveVersionUrl(String manifestJson) {
        JsonArray versions = JsonParser.parseString(manifestJson)
                .getAsJsonObject()
                .getAsJsonArray("versions");

        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (v.get("id").getAsString().equals(TARGET_VERSION)) {
                return v.get("url").getAsString();
            }
        }
        throw new RuntimeException("Version " + TARGET_VERSION + " not found in manifest.");
    }

    // ── Libraries ───────────────────────────────────────────────────────────────

    private static List<Path> resolveLibraries(JsonObject version) throws Exception {
        List<Path> paths = new ArrayList<>();
        JsonArray libraries = version.getAsJsonArray("libraries");

        for (JsonElement el : libraries) {
            JsonObject lib      = el.getAsJsonObject();

            // Skip libraries with OS rules that don't match
            if (lib.has("rules") && !matchesRules(lib.getAsJsonArray("rules"))) continue;

            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("artifact")) continue;

            JsonObject artifact = downloads.getAsJsonObject("artifact");
            String     relPath  = artifact.get("path").getAsString();
            String     url      = artifact.get("url").getAsString();
            String     sha1     = artifact.get("sha1").getAsString();

            Path dest = LIBRARIES_DIR.resolve(relPath);
            downloadIfMissing(dest, url, sha1);
            paths.add(dest);
        }

        return paths;
    }

    private static boolean matchesRules(JsonArray rules) {
        String currentOs = getCurrentOs();
        boolean allowed  = false;

        for (JsonElement el : rules) {
            JsonObject rule   = el.getAsJsonObject();
            String     action = rule.get("action").getAsString();
            boolean    hasOs  = rule.has("os");

            if (!hasOs) {
                allowed = action.equals("allow");
            } else {
                String ruleOs = rule.getAsJsonObject("os").get("name").getAsString();
                if (ruleOs.equals(currentOs)) {
                    allowed = action.equals("allow");
                }
            }
        }

        return allowed;
    }

    private static String getCurrentOs() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))   log("Windows is a heavy weight operating system. It is reccomended to run a server on Linux instead.");
        if (os.contains("mac"))   log("Warning! MacOS is Not optimized for servers! Please switch to linux!")
        if (os.contains("win"))   return "windows";
        if (os.contains("mac"))   return "osx";
        return "linux";
    }

    // ── Client JAR ──────────────────────────────────────────────────────────────

    private static Path resolveClientJar(JsonObject version) throws Exception {
        JsonObject client = version
                .getAsJsonObject("downloads")
                .getAsJsonObject("client");

        String url  = client.get("url").getAsString();
        String sha1 = client.get("sha1").getAsString();
        Path   dest = VERSIONS_DIR.resolve(TARGET_VERSION).resolve(TARGET_VERSION + ".jar");

        downloadIfMissing(dest, url, sha1);
        return dest;
    }

    // ── Launch ──────────────────────────────────────────────────────────────────

    private static void launch(List<Path> classpath, String[] extraArgs) throws Exception {
        String java   = ProcessHandle.current().info().command().orElse("java");
        String cp     = buildClasspath(classpath);
        String mainClass = "net.minecraft.client.main.Main";

        // Minimal required args Minecraft expects
        List<String> cmd = new ArrayList<>(List.of(
            java,
            "-cp", cp,
            mainClass,
            "--version",    TARGET_VERSION,
            "--accessToken", "0",           // offline mode
            "--userType",   "legacy"
        ));
        log("
        ======= WARNING: Your server is running in Offline Mode. ======= \n
        Having offline mode enabled allows any user to join your server \n
        using whatever username they want! This makes your server \n
        vunerable to exploits allowing bad actors to spoof operators. \n
        ")
        cmd.addAll(List.of(extraArgs));

        log("Spawning JVM...");
        new ProcessBuilder(cmd)
                .inheritIO()
                .start()
                .waitFor();
    }

    private static String buildClasspath(List<Path> paths) {
        StringJoiner sj = new StringJoiner(File.pathSeparator);
        for (Path p : paths) sj.add(p.toAbsolutePath().toString());
        return sj.toString();
    }

    // ── Download ─────────────────────────────────────────────────────────────────

    private static void downloadIfMissing(Path dest, String url, String expectedSha1) throws Exception {
        if (Files.exists(dest) && sha1(dest).equalsIgnoreCase(expectedSha1)) {
            log("  [cached] " + dest.getFileName());
            return;
        }

        log("  [download] " + dest.getFileName());
        Files.createDirectories(dest.getParent());

        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }

        String actual = sha1(dest);
        if (!actual.equalsIgnoreCase(expectedSha1)) {
            throw new RuntimeException("SHA1 mismatch for " + dest + ": expected " + expectedSha1 + ", got " + actual);
        }
    }

    private static String sha1(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(Files.readAllBytes(path));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────

    private static String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "SmithingLauncher/1.0");
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes());
        }
    }

    // ── Util ─────────────────────────────────────────────────────────────────────

    private static void log(String msg) {
        System.out.println("[Smithing] " + msg);
    }

    // ── Banner ───────────────────────────────────────────────────────────────────

    private static void printBanner() {
        System.out.println("""
        ╔═════════════════════════════════════════════════════════════════════════════════╗
        ║                                                                                 ║
        ║          ░██████╗███╗░░░███╗██╗████████╗██╗░░██╗██╗███╗░░██╗░██████╗░           ║
        ║          ██╔════╝████╗░████║██║╚══██╔══╝██║░░██║██║████╗░██║██╔════╝░           ║
        ║          ╚█████╗░██╔████╔██║██║░░░██║░░░███████║██║██╔██╗██║██║░░██╗░           ║
        ║          ░╚═══██╗██║╚██╔╝██║██║░░░██║░░░██╔══██║██║██║╚████║██║░░╚██╗           ║
        ║          ██████╔╝██║░╚═╝░██║██║░░░██║░░░██║░░██║██║██║░╚███║╚██████╔╝           ║
        ║          ╚═════╝░╚═╝░░░░░╚═╝╚═╝░░░╚═╝░░░╚═╝░░╚═╝╚═╝╚═╝░░╚══╝░╚═════╝░           ║
        ║                                                                                 ║
        ║                     Fabric + Bukkit — Unified Server                            ║
        ║                              Version 1.0.0-alpha                                ║
        ║                           Credit Phillip and Adrian                             ║
        ╚═════════════════════════════════════════════════════════════════════════════════╝
        """);
    }
}