package com.retiredroca.svcweb.web;

import com.retiredroca.svcweb.Config;
import com.retiredroca.svcweb.SimpleVoiceWeb;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The web listener: plain HTTP or TLS, plus the WebSocket upgrade.
 *
 * <p>{@code com.sun.net.httpserver} is not used because it cannot hand a connection over after an
 * upgrade, and the voice transport is a WebSocket. The HTTP surface is intentionally tiny: the root
 * redirects, a keyed path serves the page, and everything else is a 404.
 */
public final class WebServer {

    /** Handles one authenticated WebSocket connection. */
    public interface ConnectionHandler {
        void handle(Socket socket, InputStream in, OutputStream out, String requestHead,
                    Map<String, String> query) throws Exception;
    }

    private static final int MAX_HEAD = 16 * 1024;

    private final Config config;
    private final LinkRegistry links;
    private final ConnectionHandler connectionHandler;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SimpleVoiceWeb-Connection");
        thread.setDaemon(true);
        return thread;
    });

    private ServerSocket server;
    private volatile boolean running;

    public WebServer(Config config, LinkRegistry links, ConnectionHandler connectionHandler) {
        this.config = config;
        this.links = links;
        this.connectionHandler = connectionHandler;
    }

    public boolean isRunning() {
        return running && server != null && !server.isClosed();
    }

    public int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    /**
     * Binds the listener.
     *
     * @param tls when true the listener is wrapped in TLS using the supplied material
     */
    public synchronized void start(boolean tls, PrivateKey privateKey,
                                   List<X509Certificate> chain) throws Exception {
        if (isRunning()) {
            return;
        }
        server = tls ? tlsServer(privateKey, chain) : new ServerSocket();
        if (tls) {
            ((SSLServerSocket) server).setNeedClientAuth(false);
        } else {
            server.bind(new InetSocketAddress(config.bindAddress, config.port), 64);
        }
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "SimpleVoiceWeb-Acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        SimpleVoiceWeb.LOGGER.info("Web voice listening on {}:{} ({})", config.bindAddress,
                server.getLocalPort(), tls ? "https" : "http");
    }

    private ServerSocket tlsServer(PrivateKey privateKey, List<X509Certificate> chain)
            throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, new char[0],
                chain.toArray(new X509Certificate[0]));
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket();
        socket.bind(new InetSocketAddress(config.bindAddress, config.port), 64);
        return socket;
    }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
            server = null;
        }
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
        }
        sockets.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                workers.execute(() -> serve(socket));
            } catch (IOException e) {
                if (running) {
                    SimpleVoiceWeb.LOGGER.warn("Web listener accept failed: {}", e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String head = readHead(in);
            if (head == null) {
                return;
            }
            String[] requestLine = head.substring(0, head.indexOf("\r\n")).split(" ");
            if (requestLine.length < 2) {
                respond(out, 400, "text/plain", "bad request");
                return;
            }
            String method = requestLine[0];
            String target = requestLine[1];
            String path = target;
            Map<String, String> query = Map.of();
            int question = target.indexOf('?');
            if (question >= 0) {
                path = target.substring(0, question);
                query = parseQuery(target.substring(question + 1));
            }

            if (!method.equals("GET")) {
                respond(out, 405, "text/plain", "method not allowed");
                return;
            }

            if (path.equals("/") || path.isEmpty()) {
                redirect(out, config.redirectUrl);
                return;
            }

            if (path.equals("/ws")) {
                socket.setSoTimeout(0);
                connectionHandler.handle(socket, in, out, head, query);
                return;
            }

            if (path.startsWith("/join/")) {
                String key = path.substring("/join/".length());
                if (!links.valid(key)) {
                    respond(out, 410, "text/html; charset=utf-8",
                            Page.error(410, "This link has expired. Run the command in game again."));
                    return;
                }
                respond(out, 200, "text/html; charset=utf-8",
                        Page.html("Simple Voice Web"));
                return;
            }

            if (path.equals("/health")) {
                respond(out, 200, "text/plain", "ok");
                return;
            }

            respond(out, 404, "text/html; charset=utf-8", Page.error(404, "Not found."));
        } catch (java.net.SocketTimeoutException e) {
            // A browser that opened a connection and said nothing.
        } catch (Exception e) {
            SimpleVoiceWeb.LOGGER.debug("Web request failed: {}", e.getMessage());
        } finally {
            sockets.remove(socket);
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < MAX_HEAD) {
            int value = in.read();
            if (value < 0) {
                return null;
            }
            buffer.write(value);
            // Terminate on the empty line that ends the request head.
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : (value == '\r' ? 1 : 0);
                case 2 -> value == '\r' ? 3 : 0;
                default -> value == '\n' ? 4 : 0;
            };
            if (matched == 4) {
                return buffer.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("request head too large");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void redirect(OutputStream out, String location) throws IOException {
        String response = "HTTP/1.1 302 Found\r\n"
                + "Location: " + location + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void respond(OutputStream out, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 410 -> "Gone";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }

    /** The scheme players should use, from {@code public_url}. */
    public boolean wantsTls() {
        return config.tlsMode != Config.TlsMode.OFF;
    }
}
