import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DevServer startet einen HTTP-Server mit SSE-Unterstützung und einem File-Watcher,
 * der bei Änderungen an einer Java-Quelldatei automatisch das Programm neu ausführt
 * und verbundenen Clients über Server-Sent Events ein Reload-Signal sendet.
 * Außerdem wird die Ausgabe des Java-Prozesses in console.log geschrieben und
 * per HTTP-Server bereitgestellt.
 */
public class DevServer {
    /**
     * Config: enthält javaFile, port, verbose und Arbeitsverzeichnis
     */
    private record Config(String javaFile, int port, boolean verbose, Path dir) {}

    // Sammlung der offenen SSE-Client-Streams
    private final Set<OutputStream> clients = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private WatchService watcher;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService debounceExecutor;
    private final AtomicReference<ScheduledFuture<?>> pendingTask = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        Config cfg = parseArgs(args);
        new DevServer().start(cfg);
    }

    private static Config parseArgs(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java DevServer <JavaFile.java> [port] [--log]");
            System.exit(1);
        }
        String javaFile = null;
        int port = 8000;
        boolean verbose = false;
        for (String arg : args) {
            if ("--log".equals(arg) || "-l".equals(arg)) {
                verbose = true;
            } else if (arg.endsWith(".java") && javaFile == null) {
                javaFile = arg;
            } else {
                try { port = Integer.parseInt(arg); } catch (NumberFormatException ignored) {}
            }
        }
        if (javaFile == null) {
            System.err.println("Error: No Java file specified.");
            System.exit(1);
        }
        return new Config(javaFile, port, verbose, Paths.get("."));
    }

    private void start(Config cfg) throws Exception {
        initServer(cfg);
        initHeartbeat();
        initWatcher(cfg);
        addShutdownHook();
        server.start();
        log(cfg, "Server läuft auf http://localhost:" + cfg.port());
        watchLoop(cfg);
    }

    private void initServer(Config cfg) throws IOException {
        server = HttpServer.create(new InetSocketAddress(cfg.port()), 0);
        server.createContext("/events", this::handleSse);
        server.createContext("/", this::handleStatic);
    }

    private void handleSse(HttpExchange ex) throws IOException {
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream");
        headers.set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        clients.add(ex.getResponseBody());
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        Path dir = Paths.get(".");
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";
        Path file = dir.resolve(path.substring(1));
        if (!Files.exists(file) || Files.isDirectory(file)) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        String contentType = Files.probeContentType(file);
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type",
            contentType != null ? contentType : "application/octet-stream");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private void initHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() ->
            clients.removeIf(os -> {
                try { os.write(": heartbeat\n\n".getBytes()); os.flush(); return false; }
                catch (IOException e) { return true; }
            }), 15, 15, TimeUnit.SECONDS);
    }

    private void initWatcher(Config cfg) throws IOException {
        watcher = FileSystems.getDefault().newWatchService();
        cfg.dir().register(watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        log(cfg, "Watching " + cfg.javaFile() + "…");
    }

    private void watchLoop(Config cfg) {
        debounceExecutor = Executors.newSingleThreadScheduledExecutor();
        long debounceDelay = 200;
        while (true) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break; // sauber beenden
            }
            for (WatchEvent<?> ev : key.pollEvents()) {
                Path changed = (Path) ev.context();
                if (changed.endsWith(cfg.javaFile())) {
                    log(cfg, "Change detected: scheduling execution...");
                    ScheduledFuture<?> prev = pendingTask.getAndSet(
                        debounceExecutor.schedule(() -> runJava(cfg), debounceDelay, TimeUnit.MILLISECONDS)
                    );
                    if (prev != null && !prev.isDone()) prev.cancel(false);
                }
            }
            if (!key.reset()) break;
        }
    }

    private void runJava(Config cfg) {
        File logFile = cfg.dir().resolve("console.log").toFile();
        try (BufferedWriter logWriter = Files.newBufferedWriter(
                logFile.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // Zeitstempel an erster Stelle schreiben
            String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logWriter.write("=== Run at " + timestamp + " ===");
            logWriter.newLine();

            log(cfg, "Executing java --enable-preview " + cfg.javaFile());
            ProcessBuilder pb = new ProcessBuilder("java", "--enable-preview", cfg.javaFile())
                .directory(cfg.dir().toFile())
                .redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    logWriter.write(line);
                    logWriter.newLine();
                }
            }

            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("Timeout: process killed");
            }
            // SSE-Notification immer senden
            notifyClients();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void notifyClients() {
        clients.removeIf(os -> {
            try {
                os.write("data: reload\n\n".getBytes());
                os.flush();
                return false;
            } catch (IOException e) {
                return true;
            }
        });
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server and watcher...");
            server.stop(0);
            try { watcher.close(); } catch (IOException e) { e.printStackTrace(); }
            heartbeatExecutor.shutdownNow();
            debounceExecutor.shutdownNow();
            clients.forEach(os -> { try { os.close(); } catch (IOException ignored) {} });
        }));
    }

    private void log(Config cfg, String msg) {
        if (cfg.verbose()) System.out.println(msg);
    }
}
