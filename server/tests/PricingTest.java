package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

import static mall.Json.*;

public final class PricingTest {
    private static final Instant NOW = Instant.parse("2026-11-23T08:00:00Z");
    private static final Pricing.Context STANDARD = new Pricing.Context(1, "", "STANDARD");
    private static final ObjectNode RULE = object().put("version", 1).put("couponsEnabled", true);

    private static ObjectNode offer(int unit, int itemDiscount, int freight) {
        return object().put("skuId", "item-01").put("unitFen", unit).put("itemDiscountFen", itemDiscount)
            .put("stock", "IN_STOCK").put("eligibility", "STANDARD").put("freightFen", freight).put("feeFen", 0)
            .put("observedAt", NOW.minusSeconds(60).toString()).put("expiresAt", NOW.plusSeconds(86400).toString());
    }

    private static ObjectNode coupon(String id, String group, int threshold, int amount) {
        return object().put("id", id).put("skuId", "item-01").put("stackGroup", group)
            .put("verified", true).put("stock", "AVAILABLE").put("basis", "ITEM_AFTER_DISCOUNT").put("eligibility", "STANDARD")
            .put("claimStart", NOW.minusSeconds(60).toString()).put("useStart", NOW.minusSeconds(60).toString())
            .put("claimEnd", NOW.plusSeconds(600).toString()).put("useEnd", NOW.plusSeconds(600).toString())
            .put("thresholdFen", threshold).put("benefitFen", amount);
    }

    private static JsonNode total(ObjectNode offer) {
        return Pricing.plan(offer, STANDARD, RULE, NOW).path("estimatedTotalFen");
    }

    private static void expect(Object value, Object expected) {
        if (!expected.equals(value)) throw new AssertionError("Expected " + expected + ", received " + value);
    }

    public static void main(String[] args) {
        ObjectNode item = offer(10000, 0, 0);
        item.putArray("coupons").add(coupon("a", "store", 10000, 2000));
        expect(total(item).asInt(), 8000);
        item.put("unitFen", 9999);
        expect(total(item).asInt(), 9999);
        item.put("unitFen", 12000).put("itemDiscountFen", 1000).put("freightFen", 500);
        item.withArray("coupons").add(coupon("b", "store", 10000, 1000));
        expect(total(item).asInt(), 9500);
        item.putArray("verifiedStacks").add(array().add("a").add("b"));
        expect(total(item).asInt(), 9500);
        item.withArray("coupons").set(1, coupon("b", "platform", 10000, 1000));
        expect(total(item).asInt(), 8500);
        item.remove("verifiedStacks");
        expect(total(item).asInt(), 9500);
        item.withArray("coupons").set(0, coupon("a", "store", 10000, 0).put("discountBps", 3000)
            .put("rounding", "FLOOR").put("capFen", 2400));
        expect(total(item).asInt(), 9100);
        ((ObjectNode) item.withArray("coupons").get(0)).put("useEnd", NOW.toString());
        expect(total(item).asInt(), 10500);
        item.putNull("freightFen");
        expect(total(item).isNull(), true);
        item.put("freightFen", 500).put("eligibility", "MEMBER");
        expect(total(item).isNull(), true);
        item.put("eligibility", "STANDARD").put("observedAt", NOW.minusSeconds(86400).toString());
        expect(total(item).isNull(), true);
        System.out.println("Pricing boundaries passed");
    }
}
