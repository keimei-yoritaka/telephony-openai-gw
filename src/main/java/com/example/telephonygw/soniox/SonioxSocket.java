package com.example.telephonygw.soniox;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Ordered bounded sends, fragmented JSON receive, and late-connect cleanup. */
final class SonioxSocket implements WebSocket.Listener {
    static final Gson JSON = new Gson();
    private final Object lock;
    private final Consumer<JsonObject> receiver;
    private final Consumer<String> failure;
    private final Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector;
    private final StringBuilder fragments = new StringBuilder();
    private CompletableFuture<Void> sends = CompletableFuture.completedFuture(null);
    private WebSocket socket;
    private boolean closed;
    private boolean failed;
    private int pending;
    private long lastSend;

    SonioxSocket(Object lock, Consumer<JsonObject> receiver, Consumer<String> failure,
            Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector) {
        this.lock = lock; this.receiver = receiver; this.failure = failure; this.connector = connector;
    }

    static Function<WebSocket.Listener, CompletableFuture<WebSocket>> connector(HttpClient client, String key, String url) {
        return listener -> client.newWebSocketBuilder().header("Authorization", "Bearer " + key)
                .connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), listener);
    }

    void open() {
        try {
            connector.apply(this).thenApply(ws -> {
                synchronized (lock) {
                    if (closed || failed) { ws.abort(); throw new SonioxSessionException(true); }
                    socket = ws;
                }
                return ws;
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            abort();
            throw new SonioxSessionException(true);
        }
    }

    CompletableFuture<Void> json(Object message) { return text(JSON.toJson(message)); }
    CompletableFuture<Void> text(String message) { return send(ws -> ws.sendText(message, true)); }
    CompletableFuture<Void> binary(byte[] bytes) { return send(ws -> ws.sendBinary(ByteBuffer.wrap(bytes), true)); }

    private CompletableFuture<Void> send(Function<WebSocket, CompletableFuture<WebSocket>> action) {
        synchronized (lock) {
            if (closed || failed || socket == null) return CompletableFuture.failedFuture(new SonioxSessionException(true));
            if (pending >= 100) {
                fail("send_backlog");
                return CompletableFuture.failedFuture(new SonioxSessionException(true));
            }
            pending++;
            lastSend = System.nanoTime();
            CompletableFuture<Void> next = sends.thenCompose(ignored -> {
                synchronized (lock) {
                    if (closed || failed) return CompletableFuture.<WebSocket>failedFuture(new SonioxSessionException(true));
                    return action.apply(socket);
                }
            })
                    .orTimeout(5, TimeUnit.SECONDS).thenApply(ignored -> null);
            sends = next;
            next.whenComplete((ignored, error) -> {
                synchronized (lock) {
                    pending--;
                    if (error != null && !closed) fail("send_failed");
                }
            });
            return next;
        }
    }

    boolean quiet(long now, long nanos) { synchronized (lock) { return now - lastSend >= nanos; } }

    void abort() {
        synchronized (lock) {
            closed = true;
            if (socket != null) socket.abort();
        }
    }

    private void fail(String code) {
        if (closed || failed) return;
        failed = true;
        failure.accept(code);
    }

    @Override public void onOpen(WebSocket ws) { ws.request(1); }
    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        synchronized (lock) {
            if (!closed && !failed) {
                try {
                    if (fragments.length() + data.length() > 8 * 1024 * 1024) throw new IllegalArgumentException();
                    fragments.append(data);
                    if (last) {
                        JsonObject event = JsonParser.parseString(fragments.toString()).getAsJsonObject();
                        fragments.setLength(0);
                        receiver.accept(event);
                    }
                } catch (RuntimeException error) { fail("invalid_event"); }
            }
        }
        ws.request(1);
        return null;
    }
    @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer bytes, boolean last) {
        synchronized (lock) { fail("unexpected_binary_event"); }
        ws.request(1); return null;
    }
    @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
        synchronized (lock) { fail("transport_closed"); }
        return null;
    }
    @Override public void onError(WebSocket ws, Throwable error) { synchronized (lock) { fail("transport_error"); } }

    static String string(JsonObject event, String key) {
        return event.has(key) && !event.get(key).isJsonNull() ? event.get(key).getAsString() : "";
    }
    static boolean flag(JsonObject event, String key) {
        return event.has(key) && !event.get(key).isJsonNull() && event.get(key).getAsBoolean();
    }
}
