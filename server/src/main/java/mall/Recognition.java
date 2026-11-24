package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mall.Json.*;

final class Recognition implements AutoCloseable {
    static final int LIMIT = 10 * 1024 * 1024;
    static final String AMOUNT = "([0-9]{1,7}(?:\\.[0-9]{1,2})?)(?![0-9.])";
    static final Pattern FULL = Pattern.compile("满[¥￥]?" + AMOUNT + "元?可?减[¥￥]?" + AMOUNT);
    static final Pattern VALUE = Pattern.compile("(?:立减|优惠[券额：:]*|抵扣)[¥￥]?" + AMOUNT + "元");
    static final Pattern SCOPE = Pattern.compile("(?:适用范围|适用商品|限用|仅限)[：:]?(.+)");
    static final Pattern PERIOD = Pattern.compile("(?:有效期|使用期限|使用时间|截止|有效至)[：:]?(.+)");
    static final Pattern RELATIVE = Pattern.compile("今天|今晚|今日|明天|明日|后天|当日|^[今明]$");
    static final Pattern HAN_SPACES = Pattern.compile("(?<=[\\p{IsHan}：:])\\s+(?=[\\p{IsHan}：:])");
    static final Pattern PRODUCT = Pattern.compile("^(?:商品名称|商品|型号)[：:]\\s*(.+)");
    static final Map<String, String> LABELS = Map.of("discount", "券额", "threshold", "满额门槛",
        "period", "使用期限", "scope", "适用范围", "product", "商品线索");
    static final List<String> FIELD_ORDER = List.of("discount", "threshold", "period", "scope", "product");

    private final Store store;
    private final Map<String, Integer> uploading = new java.util.HashMap<>();
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(2);

    Recognition(Store store) {
        this.store = store;
        synchronized (store) {
            for (JsonNode item : store.table("tasks")) {
                String status = item.path("status").asText();
                if (status.equals("CREATED") || status.equals("PROCESSING")) {
                    ObjectNode task = (ObjectNode) item;
                    int attempt = task.path("attempt").asInt();
                    task.withArray("attempts").add(object().put("attempt", attempt).put("status", "INTERRUPTED").put("finishedAt", now()));
                    if (attempt >= 3) task.put("status", "FAILED").put("error", "处理多次中断，请重新提交").put("finishedAt", now());
                    else { task.put("attempt", attempt + 1); queue(task.path("id").asText(), attempt + 1); }
                }
            }
        }
        workers.scheduleWithFixedDelay(() -> {
            try { cleanup(); } catch (RuntimeException error) { System.err.println("Recognition cleanup failed"); }
        }, 0, 5, TimeUnit.MINUTES);
    }

    ObjectNode upload(String owner, boolean loggedIn, JsonNode body) throws Exception {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(string(body, "imageBase64", (LIMIT + 2) / 3 * 4)); }
        catch (IllegalArgumentException error) { throw invalid("图片编码无效"); }
        if (bytes.length < 12 || bytes.length > LIMIT) throw invalid("请选择不超过 10 MB 的图片");
        synchronized (store) {
            long count = uploading.getOrDefault(owner, 0);
            for (JsonNode upload : store.table("uploads")) if (upload.path("owner").asText().equals(owner)) count++;
            if (count >= (loggedIn ? 20 : 3)) throw new ApiError(429, "RATE_LIMITED", "图片数量已达限额，请先删除不需要的记录");
            uploading.merge(owner, 1, Integer::sum);
        }
        String id = id();
        Path original = store.dir.resolve(id + ".input");
        Path image = image(id);
        try {
            Files.write(original, bytes);
            if (process(8, env("COUPON_PYTHON", "python"), "scripts/normalize.py", original.toString(), image.toString()) != 0)
                throw invalid("图片无效。请上传一张 JPEG、PNG 或 WebP，且像素不超过 2000 万");
            synchronized (store) {
                store.table("uploads").set(id, object().put("owner", owner).put("createdAt", now()));
                store.save();
            }
            return object().put("uploadId", id);
        } finally {
            Files.deleteIfExists(original);
            synchronized (store) {
                uploading.computeIfPresent(owner, (key, count) -> count <= 1 ? null : count - 1);
                if (!store.table("uploads").has(id)) Files.deleteIfExists(image);
            }
        }
    }

    ObjectNode create(String owner, boolean loggedIn, JsonNode body, String key) {
        String type = string(body, "inputType", 12);
        String text = string(body, "text", 3000);
        String upload = string(body, "uploadId", 36);
        if (!(type.equals("text") && !text.isBlank()) && !(type.equals("image") && !upload.isBlank()))
            throw invalid("请提交一张图片或一段文字");
        synchronized (store) {
            Instant now = Instant.now();
            long daily = 0;
            long minute = 0;
            for (JsonNode item : store.table("recognitionAdmissions")) {
                if (!item.path("owner").asText().equals(owner)) continue;
                if (!key.isBlank() && item.path("key").asText().equals(key)) {
                    if (!item.path("fingerprint").asText().equals(Server.digest(type.equals("image") ? upload : text)))
                        throw conflict();
                    return view(owned("tasks", item.path("taskId").asText(), owner));
                }
                Instant created = Instant.parse(item.path("createdAt").asText());
                if (created.isAfter(now.minus(1, ChronoUnit.DAYS))) daily++;
                if (created.isAfter(now.minus(1, ChronoUnit.MINUTES))) minute++;
            }
            int budget = Integer.parseInt(env("COUPON_DAILY_TASK_BUDGET", "1000"));
            long consumed = 0;
            for (JsonNode entry : store.table("recognitionAdmissions"))
                if (Instant.parse(entry.path("createdAt").asText()).isAfter(now.minus(1, ChronoUnit.DAYS))) consumed++;
            if (consumed >= budget) throw new ApiError(429, "BUDGET_EXHAUSTED", "今日识别预算已用完，请明日再试");
            if (daily >= (loggedIn ? 20 : 3) || minute >= 5)
                throw new ApiError(429, "RATE_LIMITED", "识别次数已达限额，请稍后再试");
            if (type.equals("image")) {
                ObjectNode file = owned("uploads", upload, owner);
                if (file.has("taskId") || !Files.isRegularFile(image(upload))) throw invalid("请重新上传图片");
            }
            String id = id();
            ObjectNode task = object().put("id", id).put("owner", owner).put("key", key)
                .put("inputType", type).put("status", "CREATED").put("createdAt", now.toString())
                .put("version", 1).put("attempt", 1);
            if (type.equals("image")) {
                task.put("uploadId", upload);
                owned("uploads", upload, owner).put("taskId", id);
            } else task.put("text", text);
            store.table("tasks").set(id, task);
            store.table("recognitionAdmissions").set(id, object().put("owner", owner).put("taskId", id)
                .put("key", key).put("fingerprint", Server.digest(type.equals("image") ? upload : text))
                .put("createdAt", now.toString()));
            store.save();
            queue(id, 1);
            return view(task);
        }
    }

    ObjectNode get(String owner, String id) {
        synchronized (store) { return view(owned("tasks", id, owner)); }
    }

    ArrayNode list(String owner) {
        synchronized (store) {
            List<JsonNode> tasks = new ArrayList<>();
            store.table("tasks").forEach(item -> {
                if (item.path("owner").asText().equals(owner)) tasks.add(view((ObjectNode) item));
            });
            tasks.sort((a, b) -> b.path("createdAt").asText().compareTo(a.path("createdAt").asText()));
            ArrayNode list = array();
            tasks.forEach(list::add);
            return list;
        }
    }

    ObjectNode confirm(String owner, String id, JsonNode body) {
        synchronized (store) {
            ObjectNode task = owned("tasks", id, owner);
            checkVersion(task, body);
            String status = task.path("status").asText();
            if (!List.of("NEED_CONFIRMATION", "COMPLETED").contains(status)) throw conflict();
            JsonNode input = body.path("confirmedFields");
            if (!input.isObject()) throw invalid("请填写确认字段");
            ObjectNode confirmed = object();
            ArrayNode missing = array();
            for (String key : FIELD_ORDER) {
                String value = string(input, key, 220);
                if (key.equals("discount") || key.equals("threshold")) {
                    Integer money = fen(value);
                    if (money != null) confirmed.put(key + "Fen", money);
                }
                if (value.isBlank() && !key.equals("product")) missing.add(LABELS.get(key));
                confirmed.put(key, value);
            }
            String capture = string(input, "captureDate", 10);
            if (!capture.isBlank()) {
                try { LocalDate.parse(capture); }
                catch (Exception error) { throw invalid("请选择有效的截图日期"); }
                confirmed.put("captureDate", capture);
            }
            if (RELATIVE.matcher(confirmed.path("period").asText()).find() && capture.isBlank())
                missing.add("截图日期");
            change(task);
            if (task.has("confirmedFields")) {
                ArrayNode revisions = task.withArray("revisions");
                revisions.add(object().put("version", task.path("version").asInt() - 1)
                    .put("changedAt", now()).set("confirmedFields", task.get("confirmedFields").deepCopy()));
            }
            task.set("confirmedFields", confirmed);
            task.set("missingConditions", missing);
            task.put("status", "COMPLETED").put("finishedAt", now());
            task.remove("text");
            store.save();
            return view(task);
        }
    }

    ObjectNode retry(String owner, String id) {
        synchronized (store) {
            ObjectNode task = owned("tasks", id, owner);
            int attempt = task.path("attempt").asInt();
            if (!List.of("FAILED", "TIMEOUT").contains(task.path("status").asText()) || attempt >= 3)
                throw invalid("这条记录无法继续重试，请重新提交");
            change(task);
            task.withArray("attempts").add(object().put("attempt", attempt)
                .put("status", task.path("status").asText()).put("finishedAt", task.path("finishedAt").asText()));
            task.put("attempt", ++attempt).put("status", "CREATED");
            task.remove(List.of("error", "finishedAt"));
            store.save();
            queue(id, attempt);
            return view(task);
        }
    }

    byte[] evidence(String owner, String id) throws Exception {
        synchronized (store) {
            ObjectNode task = owned("tasks", id, owner);
            String upload = task.path("uploadId").asText();
            if (!hasImage(upload)) throw missing();
            return Files.readAllBytes(image(upload));
        }
    }

    void delete(String owner, String id) {
        synchronized (store) {
            ObjectNode task = owned("tasks", id, owner);
            removeImage(task.path("uploadId").asText());
            store.table("tasks").remove(id);
            JsonNode admission = store.table("recognitionAdmissions").path(id);
            if (admission instanceof ObjectNode item) item.remove(List.of("fingerprint", "key", "taskId"));
            auditDeletion("RECOGNITION_DELETED", id);
            store.save();
        }
    }

    void deleteAll(String owner) {
        synchronized (store) {
            for (JsonNode task : list(owner)) delete(owner, task.path("id").asText());
            List<String> uploads = new ArrayList<>();
            store.table("uploads").fields().forEachRemaining(item -> {
                if (item.getValue().path("owner").asText().equals(owner)) uploads.add(item.getKey());
            });
            uploads.forEach(this::removeImage);
            var admissions = store.table("recognitionAdmissions").fields();
            while (admissions.hasNext()) {
                if (admissions.next().getValue().path("owner").asText().equals(owner)) admissions.remove();
            }
            store.save();
        }
    }

    void cleanup() {
        synchronized (store) {
            Instant now = Instant.now();
            List<String> images = new ArrayList<>();
            List<String> tasks = new ArrayList<>();
            store.table("uploads").fields().forEachRemaining(item -> {
                JsonNode upload = item.getValue();
                JsonNode task = store.table("tasks").path(upload.path("taskId").asText());
                String date = task.path("finishedAt").asText(upload.path("createdAt").asText());
                if (!Instant.parse(date).plus(24, ChronoUnit.HOURS).minus(5, ChronoUnit.MINUTES).isAfter(now))
                    images.add(item.getKey());
            });
            store.table("tasks").fields().forEachRemaining(item -> {
                if (Instant.parse(item.getValue().path("createdAt").asText()).plus(30, ChronoUnit.DAYS).isBefore(now))
                    tasks.add(item.getKey());
            });
            List<String> admissions = new ArrayList<>();
            store.table("recognitionAdmissions").fields().forEachRemaining(item -> {
                if (Instant.parse(item.getValue().path("createdAt").asText()).plus(1, ChronoUnit.DAYS).isBefore(now))
                    admissions.add(item.getKey());
            });
            var plans = store.table("pricePlans").fields();
            while (plans.hasNext()) {
                if (Instant.parse(plans.next().getValue().path("createdAt").asText()).plus(30, ChronoUnit.DAYS).isBefore(now)) plans.remove();
            }
            images.forEach(this::removeImage);
            tasks.forEach(task -> {
                store.table("tasks").remove(task);
                auditDeletion("RECOGNITION_EXPIRED", task);
            });
            admissions.forEach(store.table("recognitionAdmissions")::remove);
            store.save();
        }
    }

    private void queue(String id, int attempt) { workers.execute(() -> run(id, attempt)); }

    private void auditDeletion(String action, String target) {
        String id = id();
        store.table("audit").set(id, object().put("id", id).put("action", action).put("target", target)
            .put("createdAt", now()));
    }

    private void run(String id, int attempt) {
        ObjectNode input;
        synchronized (store) {
            JsonNode node = store.table("tasks").path(id);
            if (!(node instanceof ObjectNode task) || task.path("attempt").asInt() != attempt) return;
            task.put("status", "PROCESSING");
            task.put("startedAt", now()).put("leaseUntil", Instant.now().plusSeconds(30).toString());
            store.save();
            input = task.deepCopy();
        }
        String status = "NEED_CONFIRMATION";
        String error = "";
        ArrayNode fields = null;
        Path output = store.dir.resolve(id + ".ocr");
        try {
            JsonNode lines;
            if (input.path("inputType").asText().equals("image")) {
                if (process(14, "powershell.exe", "-NoProfile", "-File", "scripts/ocr.ps1", "-Path",
                    image(input.path("uploadId").asText()).toString(), "-Output", output.toString()) != 0)
                    throw new IllegalStateException();
                lines = MAPPER.readTree(output.toFile());
            } else {
                ArrayNode textLines = array();
                input.path("text").asText().lines().limit(80).forEach(line -> textLines.add(object().put("text", line)));
                lines = textLines;
            }
            fields = extract(lines);
        } catch (TimeoutException timeout) {
            status = "TIMEOUT";
            error = "识别超时，可以重试";
        } catch (Exception failure) {
            status = "FAILED";
            error = "识别服务暂不可用，可以重试或粘贴文字";
        } finally {
            try { Files.deleteIfExists(output); } catch (Exception ignored) { System.err.println("OCR temp cleanup failed"); }
        }
        synchronized (store) {
            JsonNode node = store.table("tasks").path(id);
            if (!(node instanceof ObjectNode task) || task.path("attempt").asInt() != attempt) return;
            task.put("status", status).put("finishedAt", now());
            task.remove("leaseUntil");
            change(task);
            if (fields != null) {
                task.set("fields", fields);
                task.remove("text");
            } else task.put("error", error);
            store.save();
        }
    }

    static ArrayNode extract(JsonNode lines) {
        ObjectNode fields = object();
        for (JsonNode line : lines) {
            String raw = line.path("text").asText().strip();
            if (raw.length() > 220) continue;
            String compact = raw.replaceAll("\\s", "");
            Matcher full = FULL.matcher(compact);
            boolean foundFull = false;
            while (full.find()) {
                foundFull = true;
                add(fields, line, "threshold", full.group(1), raw);
                add(fields, line, "discount", full.group(2), raw);
            }
            if (!foundFull) match(fields, line, "discount", VALUE, compact, raw);
            String normalized = HAN_SPACES.matcher(raw).replaceAll("");
            match(fields, line, "scope", SCOPE, normalized, raw);
            match(fields, line, "period", PERIOD, normalized, raw);
            match(fields, line, "product", PRODUCT, normalized, raw);
        }
        ArrayNode result = array();
        for (String name : FIELD_ORDER) {
            JsonNode field = fields.path(name);
            if (!field.isMissingNode()) result.add(field);
            else result.add(object().put("name", name).put("label", LABELS.get(name)).putNull("value").put("state", "UNKNOWN"));
        }
        return result;
    }

    private static void match(ObjectNode fields, JsonNode line, String key, Pattern pattern, String candidate, String raw) {
        Matcher match = pattern.matcher(candidate);
        if (match.find()) {
            String value = match.group(1).strip();
            if (key.equals("period")) value = value.replaceAll("(?<=[0-9])\\s+(?=[年月日时分秒])|(?<=[年月日时分秒])\\s+(?=[0-9])", "");
            add(fields, line, key, value, raw);
        }
    }

    private static void add(ObjectNode fields, JsonNode line, String key, String value, String raw) {
        if (fields.has(key)) {
            ObjectNode previous = (ObjectNode) fields.get(key);
            if (!previous.path("value").asText().equals(value)) previous.putNull("value").put("state", "CONFLICT");
            return;
        }
        ObjectNode field = object().put("name", key).put("label", LABELS.get(key)).put("raw", raw)
            .put("value", value).put("state", "REVIEW");
        if (line.has("bbox")) field.set("bbox", line.get("bbox"));
        fields.set(key, field);
    }

    private static int process(int seconds, String... command) throws Exception {
        Process tool = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!tool.waitFor(seconds, TimeUnit.SECONDS)) {
            tool.destroyForcibly().waitFor();
            throw new TimeoutException();
        }
        return tool.exitValue();
    }

    private ObjectNode owned(String table, String id, String owner) {
        JsonNode item = store.table(table).path(id);
        if (!(item instanceof ObjectNode node) || !owner.equals(node.path("owner").asText())) throw missing();
        return node;
    }

    private ObjectNode view(ObjectNode task) {
        ObjectNode data = task.deepCopy().put("hasImage", hasImage(task.path("uploadId").asText()));
        data.remove(List.of("owner", "key", "uploadId", "text"));
        return data;
    }

    private Path image(String id) { return store.dir.resolve(id + ".png"); }
    private boolean hasImage(String id) { return !id.isBlank() && Files.isRegularFile(image(id)); }
    private void removeImage(String id) {
        if (id.isBlank()) return;
        try { Files.deleteIfExists(image(id)); }
        catch (Exception error) { throw new ApiError(503, "STORAGE_UNAVAILABLE", "图片暂时无法删除"); }
        store.table("uploads").remove(id);
    }

    static void checkVersion(JsonNode item, JsonNode body) {
        if (number(body, "version", 1, Integer.MAX_VALUE, 0) != item.path("version").asInt()) throw conflict();
    }

    static void change(ObjectNode item) {
        item.put("version", item.path("version").asInt() + 1).put("updatedAt", now());
    }

    @Override public void close() { workers.shutdownNow(); }
}
