package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static mall.Json.*;

final class Pricing {
    private record Coupon(JsonNode details, ObjectNode option) {}
    private record Choice(List<Coupon> coupons, long deduction) {}

    record Context(int quantity, String region, String eligibility) {
        static Context read(JsonNode input) {
            String region = string(input, "region", 30);
            String eligibility = string(input, "eligibility", 10);
            if (!List.of("", "STANDARD", "MEMBER", "NEW").contains(eligibility)) throw invalid("购买资格无效");
            return new Context(number(input, "quantity", 1, 99, 1), region, eligibility.isEmpty() ? "STANDARD" : eligibility);
        }
    }

    static ObjectNode plan(JsonNode offer, Context context, JsonNode rule, Instant now) {
        long subtotal = offer.path("unitFen").longValue() * context.quantity;
        long discount = Math.min(subtotal, offer.path("itemDiscountFen").longValue() * context.quantity);
        long basis = subtotal - discount;
        ObjectNode plan = object().put("currency", "CNY").put("quantity", context.quantity)
            .put("region", context.region).put("eligibility", context.eligibility)
            .put("itemSubtotalFen", subtotal).put("itemDiscountFen", discount)
            .put("ruleVersion", rule.path("version").asInt());
        ArrayNode missing = plan.putArray("missingConditions");
        ArrayNode options = plan.putArray("couponOptions");
        Map<String, Coupon> applicable = new LinkedHashMap<>();
        Choice best = new Choice(List.of(), 0);
        int couponIndex = 0;
        for (JsonNode coupon : offer.path("coupons")) {
            String id = coupon.path("id").asText("coupon-" + ++couponIndex);
            ObjectNode option = object().put("label", coupon.path("label").asText())
                .put("id", id)
                .put("eligibility", coupon.path("eligibility").asText());
            for (String key : List.of("thresholdFen", "benefitFen", "discountBps", "capFen", "claimEnd", "useEnd"))
                if (coupon.has(key)) option.set(key, coupon.get(key));
            String status = couponStatus(coupon, offer, context, basis, now);
            if (status.equals("可用") && !rule.path("couponsEnabled").asBoolean(true)) status = "规则版本已暂停抵扣";
            option.put("status", status);
            if (status.equals("可用")) {
                Coupon current = new Coupon(coupon, option);
                long amount = deduction(coupon, basis);
                option.put("deductionFen", amount);
                applicable.put(id, current);
                if (amount > best.deduction) best = new Choice(List.of(current), amount);
            }
            options.add(option);
        }
        JsonNode combinations = offer.path("verifiedStacks");
        if (combinations.isArray() && combinations.size() <= 20) {
            for (JsonNode stack : combinations) {
                Choice choice = stack(stack, applicable, basis);
                if (choice.deduction > best.deduction) best = choice;
            }
        }
        long remaining = basis;
        for (Coupon coupon : best.coupons) {
            long amount = deduction(coupon.details, remaining);
            coupon.option.put("selected", true).put("selectedDeductionFen", amount);
            remaining -= amount;
        }
        if (best.coupons.size() > 1) plan.put("couponNote", "按渠道已验证的顺序叠加抵扣。");
        else if (applicable.size() > 1) plan.put("couponNote", "已选择减免最多的可验证方案。其他组合未计入。");
        long couponFen = best.deduction;
        plan.put("couponFen", couponFen).put("merchandiseFen", basis - couponFen);
        JsonNode freight = context.region.isBlank() ? offer.path("freightFen")
            : offer.path("freightByRegion").path(context.region);
        if (!freight.isIntegralNumber() && offer.path("fixedFreight").asBoolean()) freight = offer.path("freightFen");
        JsonNode fee = offer.path("feeFen");
        if (freight.isIntegralNumber()) plan.put("freightFen", freight.asLong());
        else missing.add("运费待确认");
        if (fee.isIntegralNumber()) plan.put("feeFen", fee.asLong());
        else missing.add("必选费用待确认");
        String required = offer.path("eligibility").asText();
        if (!required.equals("STANDARD") && !required.equals(context.eligibility)) missing.add("购买资格待确认");
        if (!offer.path("stock").asText().equals("IN_STOCK")) missing.add("库存或销售状态不满足");
        if (!fresh(offer, now)) missing.add("报价已过期或需要重新核验");
        if (missing.isEmpty()) plan.put("estimatedTotalFen", basis - couponFen + freight.asLong() + fee.asLong());
        else plan.putNull("estimatedTotalFen");
        plan.put("status", missing.isEmpty() ? "SOURCE_ESTIMATE" : "REFERENCE_ONLY");
        return plan;
    }

    private static String couponStatus(JsonNode coupon, JsonNode offer, Context context, long basis, Instant now) {
        if (!coupon.path("verified").asBoolean() || !coupon.path("basis").asText().equals("ITEM_AFTER_DISCOUNT")
            || !coupon.path("skuId").asText().equals(offer.path("skuId").asText())
            || !coupon.path("thresholdFen").isIntegralNumber()
            || (!coupon.path("benefitFen").isIntegralNumber() && !coupon.path("discountBps").isIntegralNumber()))
            return "适用规则待确认";
        if (coupon.path("discountBps").isIntegralNumber() && !coupon.path("rounding").asText().equals("FLOOR"))
            return "舍入方式待确认";
        if (!coupon.path("stock").asText().equals("AVAILABLE")) return "券不可领取";
        try {
            if (now.isBefore(Instant.parse(coupon.path("claimStart").asText()))
                || now.isBefore(Instant.parse(coupon.path("useStart").asText()))) return "活动未开始";
            if (!now.isBefore(Instant.parse(coupon.path("claimEnd").asText()))
                || !now.isBefore(Instant.parse(coupon.path("useEnd").asText()))) return "已过期";
        } catch (Exception invalidDate) { return "有效期待确认"; }
        if (!coupon.path("eligibility").asText().equals("STANDARD")
            && !coupon.path("eligibility").asText().equals(context.eligibility)) return "购买资格待确认";
        return basis >= coupon.path("thresholdFen").asLong() ? "可用" : "未达到满额门槛";
    }

    private static Choice stack(JsonNode stack, Map<String, Coupon> applicable, long basis) {
        List<Coupon> coupons = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        if (!stack.isArray() || stack.size() < 2 || stack.size() > 5) return new Choice(List.of(), 0);
        long remaining = basis;
        for (JsonNode id : stack) {
            Coupon coupon = applicable.get(id.asText());
            if (coupon == null) return new Choice(List.of(), 0);
            String group = coupon.details.path("stackGroup").asText();
            if (group.isBlank() || !groups.add(group)) return new Choice(List.of(), 0);
            coupons.add(coupon);
            remaining -= deduction(coupon.details, remaining);
        }
        return new Choice(coupons, basis - remaining);
    }

    private static long deduction(JsonNode coupon, long basis) {
        long amount = coupon.path("discountBps").isIntegralNumber()
            ? basis * coupon.path("discountBps").asLong() / 10000 : coupon.path("benefitFen").asLong();
        long cap = coupon.path("capFen").isIntegralNumber() ? coupon.path("capFen").asLong() : basis;
        return Math.min(basis, Math.min(amount, cap));
    }

    static boolean fresh(JsonNode offer, Instant now) {
        try {
            Instant observed = Instant.parse(offer.path("observedAt").asText());
            return !observed.isAfter(now) && now.isBefore(observed.plusSeconds(86400))
                && now.isBefore(Instant.parse(offer.path("expiresAt").asText()));
        } catch (Exception error) { return false; }
    }
}
