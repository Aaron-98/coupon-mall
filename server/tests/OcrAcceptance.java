package mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static mall.Json.*;

public final class OcrAcceptance {
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]).toAbsolutePath();
        JsonNode manifest = MAPPER.readTree(folder.resolve("manifest.json").toFile());
        Map<String, int[]> totals = new LinkedHashMap<>(), clear = new LinkedHashMap<>();
        for (String field : List.of("discount", "threshold", "period", "scope")) {
            totals.put(field, new int[4]); clear.put(field, new int[4]);
        }
        int failures = 0, unsupportedAmounts = 0;
        var cases = array();
        for (JsonNode sample : manifest) {
            Path output = folder.resolve(sample.path("id").asText()+".ocr.json");
            long start = System.nanoTime();
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-File", "scripts/ocr.ps1", "-Path", folder.resolve(sample.path("image").asText()).toString(), "-Output", output.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            boolean done = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) process.destroyForcibly().waitFor();
            ObjectNode result = object().put("id",sample.path("id").asText()).put("category",sample.path("category").asText())
                .put("latencyMs", (System.nanoTime()-start)/1_000_000);
            cases.add(result);
            if (!done || process.exitValue()!=0) { failures++; result.put("error", "OCR_FAILED"); continue; }
            JsonNode fields = Recognition.extract(MAPPER.readTree(output.toFile()));
            result.set("fields", fields);
            for (JsonNode field : fields) {
                String name = field.path("name").asText();
                if (!totals.containsKey(name)) continue;
                JsonNode expected = sample.path("truth").path(name);
                boolean known = !expected.isNull(), returned = !field.path("value").isNull();
                boolean correct = expected.equals(field.path("value"));
                for (Map<String,int[]> group : List.of(totals, clear)) {
                    if (group==clear && !sample.path("category").asText().equals("clear")) continue;
                    int[] count = group.get(name);
                    if (known) { count[0]++; if (correct) count[1]++; if (returned) count[2]++; }
                    else if (returned) count[3]++;
                }
                if (!known && returned && List.of("discount","threshold").contains(name)) unsupportedAmounts++;
            }
            if (cases.size()%20==0) System.out.println("OCR samples completed: "+cases.size());
        }
        ObjectNode report = object().put("samples",manifest.size()).put("engineFailures",failures).put("unsupportedAmounts", unsupportedAmounts);
        report.set("all", summary(totals)); report.set("clear",summary(clear)); report.set("cases",cases);
        var times = new ArrayList<Long>(); cases.forEach(row -> times.add(row.path("latencyMs").asLong()));
        Collections.sort(times); report.put("ocrP95Ms",times.get((int)Math.ceil(times.size()*.95)-1));
        boolean pass = failures==0 && unsupportedAmounts==0 && times.get((int)Math.ceil(times.size()*.95)-1)<=15000;
        for (int[] count:clear.values()) pass &= count[1]>=count[0]*.95 && count[2]>=count[0]*.95;
        report.put("pass",pass);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("report.json").toFile(),report);
        ObjectNode summary = report.deepCopy(); summary.remove("cases"); System.out.println(summary);
        if (!pass) throw new AssertionError("OCR acceptance failed: "+folder.resolve("report.json"));
    }
    private static ObjectNode summary(Map<String,int[]> values) {
        ObjectNode result=object(); values.forEach((name,c)->result.set(name, object().put("labelled",c[0]).put("correct",c[1]).put("returned",c[2]).put("unexpected",c[3])
            .put("accuracy",c[0]==0?0:(double)c[1]/c[0]).put("coverage",c[0]==0?0:(double)c[2]/c[0])));
        return result;
    }
}
