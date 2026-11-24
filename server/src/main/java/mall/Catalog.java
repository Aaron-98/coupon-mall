package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static mall.Json.*;

final class Catalog {
    private static final List<String> ATTRIBUTES = List.of("brand", "model", "size", "color", "bundle", "condition", "market", "warranty");
    private static final Pattern URL = Pattern.compile("https?://[^\\s，。；、<>《》]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> HOSTS = Set.of("item.taobao.com", "detail.tmall.com", "m.tb.cn",
        "www.taobao.com", "v.douyin.com", "haohuo.jinritemai.com");
    private final Store store;
    private final boolean demo;
    private final String sourceId;
    private final SourceFeed feed;
    private final String targetApp = env("COUPON_APPROVED_TARGET_APPID", "");
    private ArrayNode demoOffers;

    Catalog(Store store, boolean demo) {
        this.store = store;
        this.demo = demo;
        sourceId = demo ? "demo-taobao" : "authorized-taobao";
        feed = demo ? null : new SourceFeed();
        if (demo) demoOffers = fixtures();
        synchronized (store) {
            if (!store.table("sources").has(sourceId))
                store.table("sources").set(sourceId, object().put("id", sourceId).put("enabled", demo).put("version", 1));
            if (store.table("rules").isEmpty()) {
                store.table("rules").set("1", object().put("version", 1).put("couponsEnabled", true));
                store.data.put("ruleVersion", 1);
            }
            store.save();
        }
    }

    ObjectNode source() {
        ObjectNode settings = (ObjectNode) store.table("sources").path(sourceId);
        ObjectNode result = settings.deepCopy().put("platform", "淘宝").put("demo", demo)
            .put("label", demo ? "淘宝联调样例" : "淘宝授权渠道");
        String state = !settings.path("enabled").asBoolean() ? "DISABLED" : demo ? "DEMO" : "UNAVAILABLE";
        if (!demo && feed.configured()) {
            try {
                JsonNode snapshot = feed.read();
                result.set("capabilities", snapshot.path("capabilities"));
                result.put("authorizationExpiresAt", snapshot.path("authorizationExpiresAt").asText());
                if (settings.path("enabled").asBoolean()) state = "AVAILABLE";
            } catch (ApiError error) {
                result.put("errorCode", error.code);
            }
        }
        return result.put("state", state);
    }

    ObjectNode products(JsonNode filter) {
        ArrayNode sourceStates = array().add(source());
        String q = string(filter, "q", 100).toLowerCase(Locale.ROOT);
        String platform = string(filter, "platform", 20);
        String category = string(filter, "category", 30);
        String cursor = string(filter, "cursor", 100);
        int limit = number(filter, "limit", 1, 20, 12);
        int min = number(filter, "minFen", 0, 999999999, 0);
        int max = number(filter, "maxFen", 0, 999999999, 999999999);
        if (min > max) throw invalid("最低价不能高于最高价");
        List<JsonNode> filtered = new ArrayList<>();
        for (JsonNode offer : offers()) {
            String id = offer.path("id").asText();
            String haystack = (offer.path("name").asText() + " " + offer.path("skuLabel").asText()
                + " " + offer.path("shop").asText()).toLowerCase(Locale.ROOT);
            JsonNode displayed = Pricing.plan(offer, new Pricing.Context(1, "", "STANDARD"), rule(), Instant.now());
            long price = displayed.path("estimatedTotalFen").isIntegralNumber()
                ? displayed.path("estimatedTotalFen").asLong() : displayed.path("merchandiseFen").asLong();
            if (id.compareTo(cursor) <= 0 || !haystack.contains(q) || price < min || price > max) continue;
            if (!platform.isBlank() && !platform.equals(offer.path("platform").asText())) continue;
            if (!category.isBlank() && !category.equals(offer.path("category").asText())) continue;
            if (Pricing.fresh(offer, Instant.now())) filtered.add(offer);
        }
        filtered.sort(Comparator.comparing(item -> item.path("id").asText()));
        ArrayNode items = array();
        filtered.stream().limit(limit).forEach(offer -> items.add(decorated(offer, new Pricing.Context(1, "", "STANDARD"))));
        ObjectNode result = object().set("items", items);
        result.set("sourceStates", sourceStates);
        result.set("sourceErrors", array());
        if (filtered.size() > limit) result.put("nextCursor", filtered.get(limit - 1).path("id").asText());
        return result;
    }

    ObjectNode details(String id, Pricing.Context context) {
        JsonNode offer = find(id);
        ObjectNode result = decorated(offer, context);
        ArrayNode skus = result.putArray("variants");
        for (JsonNode candidate : offers()) {
            if (candidate.path("productId").asText().equals(offer.path("productId").asText())
                && candidate.path("shop").asText().equals(offer.path("shop").asText())) {
                skus.add(object().put("id", candidate.path("id").asText()).put("label", candidate.path("skuLabel").asText()));
            }
        }
        return result;
    }

    ObjectNode comparisons(JsonNode body) {
        String anchorId = string(body, "offerId", 100);
        JsonNode ids = body.path("skuIds");
        if (!ids.isMissingNode() && (!ids.isArray() || ids.size() > 10)) throw invalid("最多选择 10 个规格");
        if (anchorId.isBlank() && ids.isArray() && !ids.isEmpty()) anchorId = ids.get(0).asText();
        JsonNode anchor = find(anchorId);
        Pricing.Context context = Pricing.Context.read(body.path("context"));
        List<ObjectNode> ranked = new ArrayList<>();
        ObjectNode result = object().put("title", anchor.path("name").asText()).put("skuLabel", anchor.path("skuLabel").asText())
            .put("quantity", context.quantity()).put("region", context.region()).put("eligibility", context.eligibility());
        ArrayNode references = result.putArray("referenceOnly");
        ArrayNode excluded = result.putArray("excluded");
        for (JsonNode candidate : offers()) {
            if (!candidate.path("productId").asText().equals(anchor.path("productId").asText())) continue;
            if (ids.isArray() && !ids.isEmpty() && !includes(ids, candidate.path("id").asText(), candidate.path("skuId").asText())) continue;
            ObjectNode row = decorated(candidate, context);
            String difference = difference(anchor, candidate);
            if (!difference.isEmpty()) excluded.add(row.put("reason", difference));
            else if (row.path("plan").path("estimatedTotalFen").isNull()) references.add(row.put("reason", "报价条件待确认"));
            else ranked.add(row);
        }
        ranked.sort(Comparator.<ObjectNode>comparingLong(row -> row.path("plan").path("estimatedTotalFen").asLong())
            .thenComparing(row -> Instant.parse(row.path("observedAt").asText()), Comparator.reverseOrder())
            .thenComparing(row -> row.path("id").asText()));
        ArrayNode comparable = result.putArray("comparable");
        ranked.forEach(comparable::add);
        return result.put("sufficient", ranked.size() >= 2).put("sortReason", "同规格、同数量和购买条件，按含运费预估总价升序；同价按采集时间降序");
    }

    ObjectNode replay(String id) {
        JsonNode stored = store.table("pricePlans").path(id);
        if (stored.isMissingNode()) throw missing();
        JsonNode previous = stored.path("plan");
        ObjectNode recomputed = Pricing.plan(stored.path("snapshot"), Pricing.Context.read(previous),
            stored.path("rule"), Instant.parse(stored.path("createdAt").asText()));
        return object().put("planId", id).put("matches", previous.equals(recomputed)).set("plan", recomputed);
    }

    ObjectNode history(String id) {
        JsonNode offer = find(id);
        var rows = new ArrayList<JsonNode>();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode stored : store.table("pricePlans")) {
            JsonNode snapshot = stored.path("snapshot"), plan = stored.path("plan");
            if (!snapshot.path("id").asText().equals(id) || plan.path("quantity").asInt() != 1
                || !plan.path("region").asText().isBlank() || !plan.path("eligibility").asText().equals("STANDARD")) continue;
            String observed = snapshot.path("observedAt").asText();
            if (seen.add(observed)) rows.add(object().put("observedAt", observed)
                .set("estimatedTotalFen", plan.path("estimatedTotalFen").deepCopy()));
        }
        rows.sort(Comparator.comparing(row -> row.path("observedAt").asText()));
        var points = array(); rows.forEach(points::add);
        return object().put("demo", demo).put("skuId", offer.path("skuId").asText())
            .put("context", "单件、默认地区、普通购买；仅已记录快照，缺失日期不补齐").set("points", points);
    }

    ObjectNode watch(String user, String id, JsonNode body) {
        find(id);
        int threshold = number(body, "thresholdFen", 1, 999999999, 0);
        if (threshold == 0 || !body.path("consent").asBoolean()) throw invalid("请主动确认提醒用途及目标价格");
        ObjectNode subscriptions = store.table("subscriptions").withObjectProperty(user);
        JsonNode previous = subscriptions.path(id);
        ObjectNode row = object().put("offerId", id).put("thresholdFen", threshold).put("consentVersion", "in-app-v1")
            .put("updatedAt", now()).put("channel", "IN_APP").put("enabled", true);
        if (previous.has("lastNotifiedAt")) row.set("lastNotifiedAt", previous.get("lastNotifiedAt"));
        subscriptions.set(id, row);
        store.save();
        return row.deepCopy();
    }

    ArrayNode watches(String user) { return list(store.table("subscriptions").withObjectProperty(user), 100); }

    void unwatch(String user, String id) {
        store.table("subscriptions").withObjectProperty(user).remove(id);
        store.save();
    }

    ArrayNode notifications(String user) {
        ObjectNode inbox = store.table("notifications").withObjectProperty(user);
        var iterator = inbox.fields();
        while (iterator.hasNext())
            if (Instant.parse(iterator.next().getValue().path("createdAt").asText()).plusSeconds(30L * 86400).isBefore(Instant.now())) iterator.remove();
        for (JsonNode subscription : store.table("subscriptions").path(user)) {
            if (subscription.has("lastNotifiedAt") && Instant.parse(subscription.path("lastNotifiedAt").asText()).plusSeconds(86400).isAfter(Instant.now())) continue;
            try {
                JsonNode offer = find(subscription.path("offerId").asText());
                ObjectNode row = decorated(offer, new Pricing.Context(1, "", "STANDARD"));
                JsonNode total = row.path("plan").path("estimatedTotalFen");
                if (!total.isIntegralNumber() || row.path("stale").asBoolean() || total.asLong() > subscription.path("thresholdFen").asLong()) continue;
                String id = id();
                inbox.set(id, object().put("id", id).put("offerId", offer.path("id").asText()).put("name", offer.path("name").asText())
                    .put("createdAt", now()).put("observedAt", offer.path("observedAt").asText()).put("priceFen", total.asLong())
                    .put("demo", demo).put("context", "单件、默认地区、普通购买；当前价格请进入详情核对"));
                ((ObjectNode) subscription).put("lastNotifiedAt", now());
            } catch (ApiError unavailable) { /* Disabled/expired sources cannot create recommendations. */ }
        }
        store.save();
        return list(inbox, 100);
    }

    ObjectNode resolve(JsonNode input) {
        String text = string(input, "text", 3000);
        var url = URL.matcher(text);
        if (!url.find()) throw invalid("粘贴一条支持的商品链接，也可以搜索商品关键词");
        URI uri;
        try { uri = URI.create(url.group()); }
        catch (IllegalArgumentException error) { throw invalid("链接格式有误"); }
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getPort() != -1 || !HOSTS.contains(host.toLowerCase(Locale.ROOT)))
            throw invalid("暂不支持此链接。可以改用截图或商品关键词");
        for (JsonNode offer : offers()) {
            if (uri.toString().equals(offer.path("shareUrl").asText()))
                return object().put("offerId", offer.path("id").asText());
        }
        throw new ApiError(422, "SKU_UNMATCHED", "当前渠道无法解析这条链接。请改用截图或商品关键词");
    }

    ArrayNode favorites(String user) {
        ArrayNode result = array();
        for (JsonNode favorite : store.table("favorites").path(user)) {
            String id = favorite.path("id").asText();
            try {
                JsonNode offer = find(id);
                ObjectNode item = decorated(offer, new Pricing.Context(1, "", "STANDARD"));
                item.put("available", Pricing.fresh(offer, Instant.now()));
                result.add(item);
            } catch (ApiError unavailable) {
                result.add(((ObjectNode) favorite).deepCopy().put("available", false));
            }
        }
        return result;
    }

    ObjectNode favorite(String user, String id, boolean add) {
        ObjectNode entries = store.table("favorites").withObjectProperty(user);
        if (add && !entries.has(id)) {
            JsonNode offer = find(id);
            entries.set(id, object().put("id", id).put("name", offer.path("name").asText())
                .put("skuLabel", offer.path("skuLabel").asText()).put("category", offer.path("category").asText()));
        } else if (!add) entries.remove(id);
        store.save();
        return object().put("saved", add);
    }

    ObjectNode outbound(JsonNode body) {
        if (!demo) feed.refresh();
        JsonNode offer = find(string(body, "offerId", 100));
        Pricing.Context context = Pricing.Context.read(body.path("context"));
        ObjectNode current = decorated(offer, context);
        if (!current.path("planVersion").asText().equals(string(body, "planVersion", 100)))
            throw new ApiError(409, "PRICE_CHANGED", "报价已更新，请重新确认", current);
        if (!current.path("outboundAvailable").asBoolean())
            throw new ApiError(403, "SOURCE_NOT_AUTHORIZED", "当前报价没有可用的批准跳转方式");
        return object().put("appId", targetApp).put("path", offer.path("targetPath").asText())
            .put("expiresAt", Instant.now().plusSeconds(60).toString());
    }

    ObjectNode updateSource(JsonNode body, String actor) {
        ObjectNode item = (ObjectNode) store.table("sources").path(sourceId);
        Recognition.checkVersion(item, body);
        if (!body.path("enabled").isBoolean()) throw invalid("请选择渠道状态");
        String reason = required(body, "reason", 220);
        if (!demo && body.path("enabled").asBoolean()) {
            JsonNode snapshot = feed.read();
            JsonNode evidence = snapshot.path("capabilities");
            for (String field : List.of("authorizationSubject", "scope", "requestId", "owner", "documentUrl"))
                required(evidence, field, 500);
            if (!evidence.path("verified").asBoolean())
                throw new ApiError(403, "SOURCE_NOT_AUTHORIZED", "请先完成渠道能力登记与真实请求验证");
        }
        item.put("enabled", body.path("enabled").asBoolean());
        Recognition.change(item);
        audit(actor, "SOURCE_CHANGED", sourceId, reason);
        store.save();
        return source();
    }

    ObjectNode updateOffer(String id, JsonNode body, String actor) {
        ObjectNode item = patchOffer(id);
        Recognition.checkVersion(item, body);
        if (!body.path("enabled").isBoolean()) throw invalid("请选择报价状态");
        String reason = required(body, "reason", 220);
        item.put("enabled", body.path("enabled").asBoolean());
        Recognition.change(item);
        audit(actor, "OFFER_CHANGED", id, reason);
        store.save();
        return item.deepCopy();
    }

    ObjectNode updateRule(JsonNode body, String actor) {
        int current = store.data.path("ruleVersion").asInt();
        if (number(body, "version", 1, Integer.MAX_VALUE, 0) != current) throw conflict();
        String reason = required(body, "reason", 220);
        JsonNode selected;
        if (body.has("rollbackTo")) {
            int rollback = number(body, "rollbackTo", 1, Integer.MAX_VALUE, 0);
            selected = store.table("rules").path(Integer.toString(rollback));
            if (selected.isMissingNode()) throw missing();
        } else {
            if (!body.path("couponsEnabled").isBoolean()) throw invalid("请选择抵扣规则状态");
            selected = body;
        }
        int version = store.table("rules").size() + 1;
        ObjectNode rule = object().put("version", version).put("couponsEnabled", selected.path("couponsEnabled").asBoolean())
            .put("updatedAt", now()).put("reason", reason);
        if (body.has("rollbackTo")) rule.put("rollbackTo", selected.path("version").asInt());
        store.table("rules").set(Integer.toString(version), rule);
        store.data.put("ruleVersion", version);
        audit(actor, body.has("rollbackTo") ? "RULE_ROLLED_BACK" : "RULE_PUBLISHED", Integer.toString(version), reason);
        store.save();
        return rule.deepCopy();
    }

    ObjectNode feedback(String owner, JsonNode body) {
        String objectId = string(body, "objectId", 100);
        String reason = required(body, "reason", 60);
        String description = required(body, "description", 500);
        String id = id();
        store.table("feedback").set(id, object().put("id", id).put("objectId", objectId)
            .put("reason", reason).put("description", description).put("createdAt", now())
            .put("status", "OPEN").put("version", 1).put("owner", owner));
        store.save();
        return object().put("feedbackId", id);
    }

    ObjectNode handleFeedback(String id, JsonNode body, String actor) {
        if (!(store.table("feedback").path(id) instanceof ObjectNode item)) throw missing();
        Recognition.checkVersion(item, body);
        String reason = required(body, "reason", 220);
        item.put("status", "RESOLVED").put("resolution", reason);
        Recognition.change(item);
        audit(actor, "FEEDBACK_RESOLVED", id, reason);
        store.save();
        return object().put("id", id).put("status", "RESOLVED");
    }

    ObjectNode admin() {
        ObjectNode result = object();
        result.set("source", source());
        result.set("rule", rule().deepCopy());
        result.set("rules", list(store.table("rules"), 40));
        result.set("audit", list(store.table("audit"), 50));
        ArrayNode complaints = result.putArray("feedback");
        for (JsonNode item : list(store.table("feedback"), 50)) {
            ObjectNode copy = ((ObjectNode) item).deepCopy();
            copy.remove("owner");
            complaints.add(copy);
        }
        result.put("role", "ADMIN");
        result.put("planCount", store.table("pricePlans").size());
        ArrayNode managed = result.putArray("offers");
        for (JsonNode offer : rawOffers(false)) {
            String id = offer.path("id").asText();
            ObjectNode row = patchOffer(id).deepCopy().put("id", id).put("name", offer.path("name").asText())
                .put("shop", offer.path("shop").asText()).put("skuLabel", offer.path("skuLabel").asText());
            managed.add(row);
        }
        return result;
    }

    private ObjectNode patchOffer(String id) {
        boolean exists = false;
        for (JsonNode offer : rawOffers(false)) if (offer.path("id").asText().equals(id)) exists = true;
        if (!exists) throw missing();
        ObjectNode overrides = store.table("offerSwitches");
        if (!(overrides.path(id) instanceof ObjectNode)) overrides.set(id, object().put("enabled", true).put("version", 1));
        return (ObjectNode) overrides.get(id);
    }

    private static ArrayNode list(ObjectNode table, int limit) {
        List<JsonNode> rows = new ArrayList<>();
        table.forEach(rows::add);
        ArrayNode result = array();
        for (int i = rows.size() - 1; i >= 0 && result.size() < limit; i--) result.add(rows.get(i).deepCopy());
        return result;
    }

    private void audit(String actor, String action, String target, String reason) {
        String id = id();
        store.table("audit").set(id, object().put("id", id).put("actor", actor).put("action", action)
            .put("target", target).put("reason", reason).put("createdAt", now()));
    }

    private List<JsonNode> offers() {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode offer : rawOffers(true)) {
            if (store.table("offerSwitches").path(offer.path("id").asText()).path("enabled").asBoolean(true)) rows.add(offer);
        }
        return rows;
    }

    private JsonNode rawOffers(boolean checkSource) {
        if (checkSource) {
            String state = source().path("state").asText();
            if (state.equals("DISABLED")) return array();
            if (state.equals("UNAVAILABLE")) feed.read();
        }
        if (demo) return demoOffers;
        if (!feed.configured()) return array();
        try {
            JsonNode snapshot = feed.read();
            JsonNode offers = snapshot.path("offers");
            Set<String> ids = new java.util.HashSet<>();
            for (JsonNode offer : offers) {
                validate(offer);
                if (!ids.add(offer.path("id").asText())) throw invalid("渠道报价 ID 重复");
            }
            return offers;
        } catch (ApiError error) {
            if (checkSource) throw error;
            return array();
        } catch (Exception error) {
            if (checkSource) throw new ApiError(503, "SOURCE_UNAVAILABLE", "授权渠道快照暂不可用");
            return array();
        }
    }

    private static void validate(JsonNode offer) {
        for (String key : List.of("id", "productId", "skuId", "name", "skuLabel", "platform", "sourceName", "shop", "category", "stock", "eligibility"))
            required(offer, key, 120);
        if (!offer.path("currency").asText().equals("CNY") || !offer.path("platform").asText().equals("淘宝"))
            throw invalid("报价币种或平台不支持");
        for (String field : List.of("unitFen", "itemDiscountFen", "freightFen", "feeFen")) {
            if (!offer.path(field).isNull()) number(offer, field, 0, 999999999, 0);
        }
        if (!offer.path("unitFen").isIntegralNumber()) throw invalid("缺少 SKU 单价");
        if (!offer.path("itemDiscountFen").isIntegralNumber()) throw invalid("商品优惠条件不完整");
        Instant.parse(offer.path("observedAt").asText());
        Instant.parse(offer.path("expiresAt").asText());
        offer.path("freightByRegion").fields().forEachRemaining(entry -> {
            JsonNode fee = entry.getValue();
            if (!fee.isIntegralNumber() || !fee.canConvertToInt() || fee.asInt() < 0) throw invalid("地区运费不合法");
        });
        for (JsonNode coupon : offer.path("coupons")) {
            number(coupon, "thresholdFen", 0, 999999999, 0);
            number(coupon, "benefitFen", 0, 999999999, 0);
            number(coupon, "discountBps", 1, 10000, 0);
            if (!coupon.path("capFen").isNull()) number(coupon, "capFen", 0, 999999999, 0);
        }
    }

    private JsonNode find(String id) {
        for (JsonNode offer : offers()) if (offer.path("id").asText().equals(id)) return offer;
        throw missing();
    }

    private ObjectNode decorated(JsonNode offer, Pricing.Context context) {
        Instant now = Instant.now();
        ObjectNode result = ((ObjectNode) offer).deepCopy();
        result.remove(List.of("coupons", "verifiedStacks", "targetPath", "targetAppId", "shareUrl", "freightByRegion"));
        ObjectNode plan = Pricing.plan(offer, context, rule(), now);
        result.set("plan", plan);
        String planId = Server.digest(offer.toString() + plan.toString());
        result.put("planVersion", planId).put("planId", planId);
        // Immutable inputs allow a disputed price to be replayed after a source update.
        if (!store.table("pricePlans").has(planId)) {
            store.table("pricePlans").set(planId, object().put("createdAt", now.toString())
                .set("snapshot", offer.deepCopy()));
            ObjectNode saved = (ObjectNode) store.table("pricePlans").get(planId);
            saved.set("plan", plan.deepCopy());
            saved.set("rule", rule().deepCopy());
            store.save();
        }
        result.put("demo", demo).put("fresh", Pricing.fresh(offer, now))
            .put("stale", Instant.parse(offer.path("observedAt").asText()).plusSeconds(900).isBefore(now));
        String path = offer.path("targetPath").asText();
        boolean outbound = !demo && targetApp.matches("wx[0-9a-f]{16}")
            && targetApp.equals(offer.path("targetAppId").asText()) && path.matches("pages/[a-zA-Z0-9_/?=&.%+-]{1,260}")
            && !plan.path("estimatedTotalFen").isNull() && !result.path("stale").asBoolean();
        return result.put("outboundAvailable", outbound);
    }

    private JsonNode rule() { return store.table("rules").path(store.data.path("ruleVersion").asText()); }

    private static boolean includes(JsonNode ids, String id, String sku) {
        for (JsonNode item : ids) if (item.asText().equals(id) || item.asText().equals(sku)) return true;
        return false;
    }

    private static String difference(JsonNode anchor, JsonNode candidate) {
        if (candidate.path("ad").asBoolean()) return "广告报价单列";
        for (String key : ATTRIBUTES) {
            String expected = anchor.path("attributes").path(key).asText();
            if (expected.isBlank() || !expected.equals(candidate.path("attributes").path(key).asText()))
                return "规格、版本或保修条件不同或待核对";
        }
        if (!candidate.path("attributes").path("condition").asText().equals("全新")) return "销售状态不符合严格同款口径";
        return "";
    }

    private static String required(JsonNode node, String key, int length) {
        String value = string(node, key, length);
        if (value.isBlank()) throw invalid("请填写必填项");
        return value;
    }

    private static ArrayNode fixtures() {
        Instant now = Instant.now();
        return array()
            .add(fixture("demo-01", "headset", "声屿 Air 2 无线耳机", "数码", "青柠数码店", "月岩白", 12000, 1000, 500, 2000, now))
            .add(fixture("demo-02", "headset", "声屿 Air 2 无线耳机", "数码", "云岸旗舰店", "月岩白", 10900, 0, 0, 1000, now))
            .add(fixture("demo-03", "headset", "声屿 Air 2 无线耳机", "数码", "青柠数码店", "曜石黑", 11500, 0, 500, 1000, now))
            .add(fixture("demo-04", "bottle", "漫日 随行保温杯", "日用", "漫日生活店", "雾灰 · 500 mL", 8900, 0, 0, 1000, now))
            .add(fixture("demo-05", "lamp", "微昼 可调光护眼台灯", "家居", "微昼家居店", "云白 · 标准款", 16900, 0, null, 2000, now));
    }

    private static ObjectNode fixture(String id, String product, String name, String category, String shop,
                                      String sku, int price, int off, Integer freight, int coupon, Instant now) {
        ObjectNode offer = object().put("id", id).put("productId", product).put("skuId", id)
            .put("name", name).put("category", category).put("shop", shop).put("skuLabel", sku)
            .put("platform", "淘宝").put("sourceName", "Coupon Mall 合成报价").put("currency", "CNY")
            .put("stock", "IN_STOCK").put("eligibility", "STANDARD").put("unitFen", price).put("itemDiscountFen", off)
            .put("feeFen", 0).put("fixedFreight", freight != null).put("observedAt", now.toString())
            .put("expiresAt", now.plusSeconds(86400).toString());
        if (id.equals("demo-01")) offer.put("shareUrl", "https://item.taobao.com/item.htm?id=900000000001");
        if (freight == null) offer.putNull("freightFen"); else offer.put("freightFen", freight);
        offer.putObject("attributes").put("brand", name.split(" ")[0]).put("model", product)
            .put("size", product.equals("bottle") ? "500 mL" : "标准款").put("color", sku)
            .put("bundle", "单件").put("condition", "全新").put("market", "中国大陆").put("warranty", "一年");
        ArrayNode coupons = offer.putArray("coupons");
        coupons.add(coupon(id, "店铺优惠券", id.equals("demo-04") ? 8000 : 10000, coupon, now));
        if (id.equals("demo-01")) coupons.add(coupon(id, "平台互斥券", 10000, 1000, now));
        return offer;
    }

    private static ObjectNode coupon(String sku, String label, int threshold, int benefit, Instant now) {
        return object().put("skuId", sku).put("label", label).put("thresholdFen", threshold).put("benefitFen", benefit)
            .put("verified", true).put("basis", "ITEM_AFTER_DISCOUNT").put("stock", "AVAILABLE")
            .put("eligibility", "STANDARD").put("claimStart", now.minusSeconds(3600).toString())
            .put("useStart", now.minusSeconds(3600).toString()).put("claimEnd", now.plusSeconds(86400).toString())
            .put("useEnd", now.plusSeconds(86400).toString());
    }
}
