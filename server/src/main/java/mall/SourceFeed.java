package mall;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import static mall.Json.*;

/** Configured supplier contract, never a URL supplied by an end user. */
final class SourceFeed {
    private final Path file;
    private final String endpoint = env("COUPON_FEED_URL", "");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile JsonNode snapshot;
    private volatile String failure = "SOURCE_UNAVAILABLE";
    private volatile Instant lastSuccess;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    SourceFeed() {
        String path = env("COUPON_AUTHORIZED_FEED", "");
        file = path.isBlank() ? null : Path.of(path);
        if (!endpoint.isBlank()) {
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("COUPON_FEED_URL must be a configured HTTPS supplier endpoint");
            scheduler.scheduleWithFixedDelay(this::refresh, 0, 60, TimeUnit.SECONDS);
        }
    }

    synchronized void refresh() {
        try {
            if (endpoint.isBlank()) {
                if (file == null) throw new IllegalStateException();
                snapshot = MAPPER.readTree(file.toFile());
            } else {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5));
                String token = env("COUPON_FEED_TOKEN", "");
                if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
                var pending = client.sendAsync(request.GET().build(), info -> new LimitedBody());
                HttpResponse<byte[]> response;
                try { response = pending.get(5, TimeUnit.SECONDS); }
                catch (Exception error) { pending.cancel(true); throw error; }
                    if (response.statusCode() != 200) {
                        failure = response.statusCode() == 429 ? "SOURCE_RATE_LIMITED"
                            : response.statusCode() == 401 || response.statusCode() == 403 ? "SOURCE_NOT_AUTHORIZED" : "SOURCE_UNAVAILABLE";
                        snapshot = null;
                        return;
                    }
                    snapshot = MAPPER.readTree(response.body());
            }
            if (!snapshot.path("offers").isArray()) throw new IllegalArgumentException();
            failure = "";
            lastSuccess = Instant.now();
        } catch (Exception error) {
            snapshot = null;
            failure = "SOURCE_UNAVAILABLE";
        }
    }

    JsonNode read() {
        if (endpoint.isBlank()) refresh();
        JsonNode current = snapshot;
        if (current == null || lastSuccess == null || lastSuccess.plusSeconds(180).isBefore(Instant.now()))
            throw new ApiError(failure.equals("SOURCE_NOT_AUTHORIZED") ? 403 : 503,
                failure.isBlank() ? "SOURCE_UNAVAILABLE" : failure, "授权渠道暂不可用，请稍后重试或使用截图整理");
        try {
            if (!current.path("capabilities").path("verified").asBoolean())
                throw new ApiError(403, "SOURCE_NOT_AUTHORIZED", "渠道能力登记未核验或已撤销");
            if (!Instant.now().isBefore(Instant.parse(current.path("authorizationExpiresAt").asText())))
                throw new ApiError(403, "SOURCE_NOT_AUTHORIZED", "渠道授权已到期，已停止推荐");
        } catch (ApiError error) { throw error; }
        catch (Exception error) { throw new ApiError(503, "SOURCE_UNAVAILABLE", "渠道授权信息不完整"); }
        return current;
    }

    boolean configured() { return file != null || !endpoint.isBlank(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (output.size() + (long) buffer.remaining() > 8 * 1024 * 1024) {
                    subscription.cancel(); result.completeExceptionally(new IllegalArgumentException("Supplier payload too large")); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
