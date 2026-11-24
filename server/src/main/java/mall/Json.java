package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static ArrayNode array() { return MAPPER.createArrayNode(); }
    static String id() { return UUID.randomUUID().toString(); }
    static String now() { return Instant.now().toString(); }
    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String string(JsonNode node, String key, int limit) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > limit) throw invalid("字段格式有误：" + key);
        return value.textValue().strip();
    }

    static int number(JsonNode node, String key, int min, int max, int fallback) {
        JsonNode value = node.path(key);
        if (value.isMissingNode()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw invalid("字段超出范围：" + key);
        return value.intValue();
    }

    static Integer fen(String value) {
        if (value.isEmpty()) return null;
        if (!value.matches("[0-9]{1,7}(\\.[0-9]{1,2})?")) throw invalid("请输入有效金额，最多两位小数");
        return new BigDecimal(value).movePointRight(2).intValueExact();
    }

    static ApiError invalid(String message) { return new ApiError(400, "INVALID_INPUT", message); }
    static ApiError missing() { return new ApiError(404, "NOT_FOUND", "记录不存在或已删除"); }
    static ApiError conflict() { return new ApiError(409, "VERSION_CONFLICT", "记录已更新，请刷新后再试"); }
    static ApiError forbidden() { return new ApiError(403, "FORBIDDEN", "当前会话无权执行此操作"); }

    static final class ApiError extends RuntimeException {
        final int status;
        final String code;
        final JsonNode data;
        ApiError(int status, String code, String message) { this(status, code, message, null); }
        ApiError(int status, String code, String message, JsonNode data) {
            super(message);
            this.status = status;
            this.code = code;
            this.data = data;
        }
    }

    static final class Store {
        final Path dir;
        final ObjectNode data;

        Store(Path dir) throws Exception {
            this.dir = Files.createDirectories(dir).toAbsolutePath();
            Path state = this.dir.resolve("state.json");
            data = Files.exists(state) ? (ObjectNode) MAPPER.readTree(state.toFile()) : object();
        }

        ObjectNode table(String key) {
            return data.has(key) ? (ObjectNode) data.get(key) : data.putObject(key);
        }

        void save() {
            try {
                Path temp = dir.resolve("state.tmp");
                MAPPER.writeValue(temp.toFile(), data);
                // Windows indexing/antivirus can briefly hold the destination open.
                for (int attempt = 0; ; attempt++) {
                    try {
                        Files.move(temp, dir.resolve("state.json"), REPLACE_EXISTING, ATOMIC_MOVE);
                        break;
                    } catch (java.nio.file.AccessDeniedException locked) {
                        if (attempt >= 5) throw locked;
                        Thread.sleep(20L * (attempt + 1));
                    }
                }
            } catch (Exception failure) {
                System.err.println("Store save failed: " + failure.getClass().getSimpleName());
                throw new ApiError(503, "STORAGE_UNAVAILABLE", "存储暂时不可用");
            }
        }
    }
}
