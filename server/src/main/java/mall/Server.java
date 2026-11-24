package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;

import static mall.Json.*;

public final class Server {
    private static final int MAX_BODY = 14 * 1024 * 1024;
    private static final HttpClient WECHAT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Store store;
    private final Recognition recognition;
    private final Catalog catalog;
    private final Telemetry telemetry;
    private final java.util.Map<String, long[]> limits = new java.util.HashMap<>();
    private final boolean demo;
    private final String adminKey;
    private final String wechatAppId = env("COUPON_WX_APP_ID", "");
    private final String wechatSecret = env("COUPON_WX_APP_SECRET", "");

    private record Session(String owner, boolean loggedIn) {}
    private record Reply(int status, JsonNode data) {}

    private Server(boolean demo) throws Exception {
        this.demo = demo;
        adminKey = env("COUPON_ADMIN_KEY", "");
        store = new Store(Path.of(env("COUPON_DATA_DIR", demo ? "data-demo" : "data-private")));
        recognition = new Recognition(store);
        catalog = new Catalog(store, demo);
        telemetry = new Telemetry(store);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 8787 : Integer.parseInt(args[0]);
        boolean demo = args.length > 1 && args[1].equals("demo");
        Server app = new Server(demo);
        HttpServer http = HttpServer.create(new InetSocketAddress(env("COUPON_BIND", "127.0.0.1"), port), 64);
        http.createContext("/api/v1", app::handle);
        http.setExecutor(Executors.newFixedThreadPool(16));
        http.start();
        System.out.println("Coupon Mall API listening on port " + port + (demo ? " (demo fixtures)" : ""));
    }

    private void handle(HttpExchange request) throws IOException {
        String requestId = id();
        try (request) {
            request.getResponseHeaders().set("Cache-Control", "no-store");
            request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            request.getResponseHeaders().set("X-Request-Id", requestId);
            try {
                String path = request.getRequestURI().getPath().substring("/api/v1".length());
                if (request.getRequestMethod().equals("GET") && path.matches("/recognitions/[0-9a-f-]{36}/image")) {
                    String task = path.split("/")[2];
                    send(request, 200, "image/png", recognition.evidence(session(request).owner, task));
                    return;
                }
                if (request.getRequestMethod().equals("POST") && (path.equals("/sessions/guest") || path.equals("/uploads") || path.equals("/recognitions") || path.equals("/events")))
                    rateLimit(request, path);
                Reply reply = route(request, path);
                if (reply.status == 204) request.sendResponseHeaders(204, -1);
                else envelope(request, reply.status, "OK", "成功", reply.data, requestId);
            } catch (ApiError error) {
                envelope(request, error.status, error.code, error.getMessage(), error.data, requestId);
            } catch (Exception unexpected) {
                System.err.println(requestId + " " + unexpected.getClass().getSimpleName());
                envelope(request, 503, "SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试", null, requestId);
            }
        }
    }

    private Reply route(HttpExchange request, String path) throws Exception {
        String method = request.getRequestMethod();
        if (method.equals("POST") && "PATCH".equals(request.getRequestHeaders().getFirst("X-HTTP-Method-Override"))) method = "PATCH";
        if (path.equals("/health") && method.equals("GET")) return ok(object().put("demo", demo));
        if (path.equals("/sessions/guest") && method.equals("POST")) return ok(issue("guest-" + id(), false));
        if (path.equals("/sessions/login") && method.equals("POST")) {
            Session previous = session(request);
            JsonNode body = body(request);
            String user;
            if (demo) user = previous.loggedIn ? previous.owner : "demo-" + previous.owner;
            else user = login(string(body, "code", 160));
            synchronized (store) {
                if (!previous.loggedIn) {
                    store.table("sessions").remove(digest(token(request)));
                    for (String table : List.of("tasks", "uploads", "recognitionAdmissions")) {
                        store.table(table).forEach(item -> {
                            if (item.path("owner").asText().equals(previous.owner)) ((ObjectNode) item).put("owner", user);
                        });
                    }
                }
                return ok(issue(user, true));
            }
        }
        if (path.equals("/uploads") && method.equals("POST")) {
            Session current = session(request);
            return ok(recognition.upload(current.owner, current.loggedIn, body(request)));
        }
        if (path.equals("/recognitions") && method.equals("POST")) {
            Session current = session(request);
            String key = request.getRequestHeaders().getFirst("Idempotency-Key");
            if (key != null && !key.matches("[a-zA-Z0-9_-]{1,100}")) throw invalid("提交编号无效");
            return new Reply(202, recognition.create(current.owner, current.loggedIn, body(request), key == null ? "" : key));
        }
        if (path.equals("/recognitions") && method.equals("GET")) return ok(recognition.list(session(request).owner));
        String[] parts = path.split("/");
        if (parts.length >= 3 && parts[1].equals("recognitions")) {
            String owner = session(request).owner;
            String id = parts[2];
            if (parts.length == 3 && method.equals("GET")) return ok(recognition.get(owner, id));
            if (parts.length == 4 && parts[3].equals("confirmation") && method.equals("PATCH"))
                return ok(recognition.confirm(owner, id, body(request)));
            if (parts.length == 4 && parts[3].equals("retry") && method.equals("POST"))
                return new Reply(202, recognition.retry(owner, id));
        }
        if (method.equals("DELETE") && parts.length == 4 && parts[1].equals("me") && parts[2].equals("recognitions")) {
            recognition.delete(session(request).owner, parts[3]);
            return new Reply(204, null);
        }
        if (path.equals("/me") && method.equals("DELETE")) {
            Session current = session(request);
            synchronized (store) {
                recognition.deleteAll(current.owner);
                store.table("favorites").remove(current.owner);
                store.table("subscriptions").remove(current.owner);
                store.table("notifications").remove(current.owner);
                store.table("sessions").fields().forEachRemaining(entry -> {
                    if (entry.getValue().path("owner").asText().equals(current.owner))
                        ((ObjectNode) entry.getValue()).put("expiresAt", Instant.EPOCH.toString());
                });
                var events = store.table("events").fields();
                while (events.hasNext()) if (events.next().getValue().path("session").asText().equals(digest(current.owner))) events.remove();
                store.table("feedback").forEach(item -> {
                    if (item.path("owner").asText().equals(current.owner)) ((ObjectNode) item).remove("owner");
                });
                store.save();
            }
            return new Reply(204, null);
        }
        if (path.equals("/events") && method.equals("POST")) return ok(telemetry.record(session(request).owner, body(request)));
        if (path.startsWith("/admin/")) return admin(request, method, path, parts);
        JsonNode input = List.of("POST", "PUT", "PATCH").contains(method) ? body(request) : query(request);
        synchronized (store) {
            if (path.equals("/sources") && method.equals("GET")) return ok(array().add(catalog.source()));
            if (path.equals("/products") && method.equals("GET")) return ok(catalog.products(input));
            if (parts.length == 4 && parts[1].equals("products") && parts[3].equals("offers") && method.equals("GET"))
                return ok(catalog.details(parts[2], Pricing.Context.read(input)));
            if (parts.length == 4 && parts[1].equals("products") && parts[3].equals("history") && method.equals("GET"))
                return ok(catalog.history(parts[2]));
            if (path.equals("/resolve") && method.equals("POST")) return ok(catalog.resolve(input));
            if (path.equals("/comparisons") && method.equals("POST")) return ok(catalog.comparisons(input));
            if (path.equals("/outbound-targets") && method.equals("POST")) return ok(catalog.outbound(input));
            if (path.equals("/favorites") && method.equals("GET")) return ok(catalog.favorites(user(request)));
            if (path.equals("/subscriptions") && method.equals("GET")) return ok(catalog.watches(user(request)));
            if (path.equals("/me/notifications") && method.equals("GET")) return ok(catalog.notifications(user(request)));
            if (parts.length == 3 && parts[1].equals("subscriptions") && method.equals("PUT"))
                return ok(catalog.watch(user(request), parts[2], input));
            if (parts.length == 3 && parts[1].equals("subscriptions") && method.equals("DELETE")) {
                catalog.unwatch(user(request), parts[2]); return new Reply(204, null);
            }
            if (parts.length == 3 && parts[1].equals("favorites") && method.equals("PUT"))
                return ok(catalog.favorite(user(request), parts[2], true));
            if (parts.length == 3 && parts[1].equals("favorites") && method.equals("DELETE")) {
                catalog.favorite(user(request), parts[2], false);
                return new Reply(204, null);
            }
            if (path.equals("/feedback") && method.equals("POST")) return ok(catalog.feedback(session(request).owner, input));
        }
        throw missing();
    }

    private Reply admin(HttpExchange request, String method, String path, String[] parts) throws Exception {
        String supplied = request.getRequestHeaders().getFirst("X-Admin-Key");
        String role = matches(supplied, adminKey) ? "ADMIN"
            : matches(supplied, env("COUPON_OPERATOR_KEY", "")) ? "OPERATOR"
            : matches(supplied, env("COUPON_AUDITOR_KEY", "")) ? "AUDITOR" : "";
        if (role.isBlank()) throw forbidden();
        if (!method.equals("GET") && (role.equals("AUDITOR") || (role.equals("OPERATOR") &&
            !(path.startsWith("/admin/offers/") || path.startsWith("/admin/feedback/"))))) throw forbidden();
        String actor = role.toLowerCase() + "-" + digest(supplied).substring(0, 12);
        JsonNode input = method.equals("PATCH") ? body(request) : object();
        synchronized (store) {
            if (path.equals("/admin/dashboard") && method.equals("GET")) {
                ObjectNode dashboard = catalog.admin().put("role", role);
                dashboard.set("metrics", telemetry.metrics());
                return ok(dashboard);
            }
            if (parts.length == 4 && parts[2].equals("plans") && method.equals("GET")) return ok(catalog.replay(parts[3]));
            if (path.equals("/admin/sources") && method.equals("GET")) return ok(array().add(catalog.source()));
            if (parts.length == 4 && parts[2].equals("sources") && parts[3].equals(catalog.source().path("id").asText())
                && method.equals("PATCH")) return ok(catalog.updateSource(input, actor));
            if (parts.length == 4 && parts[2].equals("offers") && method.equals("PATCH"))
                return ok(catalog.updateOffer(parts[3], input, actor));
            if (parts.length == 4 && parts[2].equals("feedback") && method.equals("PATCH"))
                return ok(catalog.handleFeedback(parts[3], input, actor));
            if (path.equals("/admin/rules") && method.equals("PATCH")) return ok(catalog.updateRule(input, actor));
        }
        throw missing();
    }

    private static boolean matches(String supplied, String expected) {
        return supplied != null && expected.length() >= 12 && MessageDigest.isEqual(utf8(supplied), utf8(expected));
    }

    private synchronized void rateLimit(HttpExchange request, String path) {
        long minute = System.currentTimeMillis() / 60000;
        String bucket = request.getRemoteAddress().getAddress().getHostAddress() + ":" + path;
        limits.entrySet().removeIf(item -> item.getValue()[0] < minute);
        long[] count = limits.computeIfAbsent(bucket, key -> new long[] {minute, 0});
        int limit = path.equals("/events") ? 6000 : path.equals("/sessions/guest") ? 180 : 120;
        if (++count[1] > limit) throw new ApiError(429, "RATE_LIMITED", "请求过于频繁，请稍后再试");
    }

    private String login(String code) throws Exception {
        if (wechatAppId.isBlank() || wechatSecret.isBlank())
            throw new ApiError(503, "LOGIN_UNAVAILABLE", "微信登录尚未配置，仍可使用游客识别");
        if (code.isBlank()) throw invalid("缺少微信登录凭证");
        String query = "appid=" + encode(wechatAppId) + "&secret=" + encode(wechatSecret)
            + "&js_code=" + encode(code) + "&grant_type=authorization_code";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/sns/jscode2session?" + query))
            .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> reply = WECHAT.send(request, HttpResponse.BodyHandlers.ofString());
        if (reply.statusCode() != 200) throw new ApiError(503, "LOGIN_UNAVAILABLE", "微信登录暂不可用");
        String openId = MAPPER.readTree(reply.body()).path("openid").asText();
        if (openId.isBlank()) throw new ApiError(401, "LOGIN_REQUIRED", "微信登录凭证失效，请重试");
        return "wx-" + digest(wechatAppId + ":" + openId);
    }

    private ObjectNode issue(String owner, boolean loggedIn) {
        String token = id() + id();
        synchronized (store) {
            Instant now = Instant.now();
            var sessions = store.table("sessions").fields();
            while (sessions.hasNext()) {
                var item = sessions.next();
                if (!Instant.parse(item.getValue().path("expiresAt").asText()).isAfter(now)) sessions.remove();
            }
            store.table("sessions").set(digest(token), object().put("owner", owner).put("loggedIn", loggedIn)
                .put("expiresAt", now.plusSeconds(30L * 86400).toString()));
            store.save();
        }
        return object().put("token", token).put("loggedIn", loggedIn).put("demo", demo);
    }

    private Session session(HttpExchange request) {
        synchronized (store) {
            JsonNode found = store.table("sessions").path(digest(token(request)));
            if (found.isMissingNode() || !Instant.now().isBefore(Instant.parse(found.path("expiresAt").asText())))
                throw new ApiError(401, "LOGIN_REQUIRED", "会话已失效，请重试");
            return new Session(found.path("owner").asText(), found.path("loggedIn").asBoolean());
        }
    }

    private String user(HttpExchange request) {
        Session current = session(request);
        if (!current.loggedIn) throw new ApiError(401, "LOGIN_REQUIRED", "请先登录后使用收藏");
        return current.owner;
    }

    private static String token(HttpExchange request) {
        String value = request.getRequestHeaders().getFirst("Authorization");
        return value != null && value.startsWith("Bearer ") ? value.substring(7) : "";
    }

    private static JsonNode body(HttpExchange request) throws IOException {
        int max = request.getRequestURI().getPath().equals("/api/v1/uploads") ? MAX_BODY : 64 * 1024;
        byte[] bytes = request.getRequestBody().readNBytes(max + 1);
        if (bytes.length > max) throw new ApiError(413, "INVALID_INPUT", "请求超过大小限制");
        if (bytes.length == 0) return object();
        try {
            JsonNode input = MAPPER.readTree(bytes);
            if (input == null || !input.isObject()) throw invalid("请求格式有误");
            return input;
        } catch (IOException badJson) { throw invalid("请求格式有误"); }
    }

    private static JsonNode query(HttpExchange request) {
        ObjectNode data = object();
        String q = request.getRequestURI().getRawQuery();
        if (q == null) return data;
        for (String item : q.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length != 2) continue;
            try {
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (List.of("limit", "minFen", "maxFen", "quantity").contains(key)) data.put(key, Integer.parseInt(val));
                else data.put(key, val);
            } catch (IllegalArgumentException error) { throw invalid("查询参数有误"); }
        }
        return data;
    }

    private static Reply ok(JsonNode data) { return new Reply(200, data); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(value))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static void envelope(HttpExchange request, int status, String code, String message, JsonNode data, String requestId)
        throws IOException {
        ObjectNode envelope = object().put("code", code).put("message", message).put("requestId", requestId);
        envelope.set("data", data);
        send(request, status, "application/json; charset=utf-8", MAPPER.writeValueAsBytes(envelope));
    }

    private static void send(HttpExchange request, int status, String type, byte[] bytes) throws IOException {
        request.getResponseHeaders().set("Content-Type", type);
        request.sendResponseHeaders(status, bytes.length);
        request.getResponseBody().write(bytes);
    }
}
