package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import static mall.Json.*;

/** No free text, URLs, eligibility, tokens or image content are accepted. */
final class Telemetry {
    private static final Set<String> EVENTS = Set.of("input_submitted", "recognition_finished", "recognition_confirmed",
        "offer_viewed", "comparison_finished", "outbound_started", "outbound_failed", "favorite_changed", "price_feedback_created");
    private final Store store;
    Telemetry(Store store) { this.store = store; }

    ObjectNode record(String owner, JsonNode body) {
        String event = string(body, "event", 40);
        String eventId = string(body, "eventId", 100);
        if (!EVENTS.contains(event) || !eventId.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("埋点事件无效");
        synchronized (store) {
            ObjectNode entries = store.table("events");
            String key = Server.digest(owner + ":" + eventId);
            if (entries.has(key)) return object().put("accepted", false);
            ObjectNode row = object().put("event", event).put("createdAt", now()).put("session", Server.digest(owner));
            for (String field : List.of("offerId", "planId", "taskId")) {
                String value = string(body, field, 100);
                if (!value.isBlank()) {
                    if (!value.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("埋点标识无效");
                    row.put(field, value);
                }
            }
            // Reject unexpected data instead of silently storing sensitive properties.
            body.fieldNames().forEachRemaining(field -> {
                if (!Set.of("event", "eventId", "offerId", "planId", "taskId").contains(field)) throw invalid("埋点字段不支持");
            });
            entries.set(key, row);
            var iterator = entries.fields();
            while (iterator.hasNext()) {
                if (Instant.parse(iterator.next().getValue().path("createdAt").asText()).plusSeconds(30L * 86400).isBefore(Instant.now())) iterator.remove();
            }
            store.save();
            return object().put("accepted", true);
        }
    }

    ObjectNode metrics() {
        ObjectNode counts = object();
        Set<String> views = new HashSet<>(), outbound = new HashSet<>();
        for (JsonNode row : store.table("events")) {
            String event = row.path("event").asText();
            counts.put(event, counts.path(event).asInt() + 1);
            if (event.equals("offer_viewed")) views.add(row.path("session").asText());
            if (event.equals("outbound_started")) outbound.add(row.path("session").asText());
        }
        ObjectNode result = object().put("viewSessions", views.size()).put("outboundSessions", outbound.size());
        result.set("events", counts);
        if (views.isEmpty()) result.putNull("outboundRate");
        else result.put("outboundRate", (double) outbound.stream().filter(views::contains).count() / views.size());
        return result;
    }
}
