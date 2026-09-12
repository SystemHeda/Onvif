package ir.alamoot.plus;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal local HTTP server (port 8080):
 *   GET /            -> status page
 *   GET /play?u=<url> -> callback with the stream URL (remote control)
 */
public class LocalWebServer {

    public interface PlayCallback { void onPlay(String url); }

    private static ServerSocket server;
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static volatile PlayCallback callback;

    public static synchronized void start(Context ctx, int port, PlayCallback cb) {
        callback = cb;
        if (server != null && !server.isClosed()) return;
        try {
            server = new ServerSocket(port);
            pool.execute(() -> acceptLoop());
        } catch (IOException ignored) {}
    }

    public static synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
    }

    private static void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) { return; }
        }
    }

    private static void handle(Socket s) {
        try (Socket sock = s;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = sock.getOutputStream()) {
            String line = in.readLine();
            if (line == null) return;
            String path = line.split(" ")[1];
            // drain headers
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip */ }

            String body;
            if (path.startsWith("/play?")) {
                String u = param(path, "u");
                if (u != null && callback != null) callback.onPlay(URLDecoder.decode(u, "UTF-8"));
                body = "{\"ok\":true,\"playing\":" + (u != null) + "}";
                respond(out, 200, "application/json", body);
            } else {
                body = "<html><head><meta charset='utf-8'><title>AlamootPlus</title></head>" +
                       "<body><h1>AlamootPlus WebServer</h1>" +
                       "<p>GET /play?u=rtsp%3A%2F%2F... to play a stream.</p></body></html>";
                respond(out, 200, "text/html; charset=utf-8", body);
            }
        } catch (Exception ignored) {}
    }

    private static String param(String path, String key) {
        String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
        }
        return null;
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type +
                "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
