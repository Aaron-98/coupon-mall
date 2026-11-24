package mall;

import java.nio.file.*;
import java.time.*;
import static mall.Json.*;

public final class LifecycleTest {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("coupon-lifecycle-");
        Store store = new Store(folder);
        try (Recognition recognition = new Recognition(store)) {
            synchronized (store) {
                String old = Instant.now().minusSeconds(31L * 86400).toString();
                String upload = id(), task = id();
                Files.write(folder.resolve(upload+".png"), new byte[]{1,2,3});
                store.table("uploads").set(upload, object().put("owner","owner-a").put("taskId",task).put("createdAt",old));
                store.table("tasks").set(task, object().put("id",task).put("owner","owner-a").put("uploadId",upload)
                    .put("createdAt",old).put("finishedAt",old).put("status","COMPLETED"));
                recognition.cleanup();
                check(!Files.exists(folder.resolve(upload+".png")), "expired image still exists");
                check(store.table("tasks").isEmpty(), "expired task still exists");
                check(store.table("uploads").isEmpty(), "expired upload still exists");
                check(!store.table("audit").isEmpty(), "deletion audit missing");
                String textTask=id();
                // Use a separate fixture to check confirmation revisions and unknown fields.
                var taskRow=object().put("id",textTask).put("owner","owner-a").put("status","NEED_CONFIRMATION").put("version",2).put("createdAt",now());
                store.table("tasks").set(textTask,taskRow);
                var input=object().put("version",2).set("confirmedFields",object().put("discount","20").put("threshold","100").put("period","今晚").put("scope","指定店铺"));
                var saved=recognition.confirm("owner-a",textTask,input);
                check(saved.path("missingConditions").toString().contains("截图日期"),"relative date silently resolved");
                var next=object().put("version",3).set("confirmedFields",object().put("discount","10").put("captureDate","2026-11-23"));
                saved=recognition.confirm("owner-a",textTask,next);
                check(saved.path("revisions").get(0).path("confirmedFields").path("discount").asText().equals("20"),"revision was lost");
                try { recognition.get("owner-b",textTask); throw new AssertionError("ownership bypass"); } catch(ApiError error) { check(error.status==404,"wrong ownership response"); }
                recognition.deleteAll("owner-a");
                check(store.table("tasks").isEmpty(),"account deletion left history");
                store.save();
                check(new Store(folder).table("tasks").isEmpty(),"deletion did not persist");
            }
        }
        var fields=Recognition.extract(array().add(object().put("text","适用商品：指定店铺")));
        check(fields.get(4).path("value").isNull(),"scope invented a product association");
        System.out.println("Retention, deletion, revisions, relative dates and owner isolation passed");
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
