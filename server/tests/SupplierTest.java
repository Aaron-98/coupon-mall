package mall;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static mall.Json.*;

/** Real HTTPS transport against a local fixture, never proof of supplier authorization. */
public final class SupplierTest {
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(args[0]))) { keys.load(input, args[1].toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys,args[1].toCharArray());
        SSLContext context=SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(),null,null);
        int port=URI.create(System.getenv("COUPON_FEED_URL")).getPort();
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",port),10);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        ExecutorService executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        AtomicInteger status=new AtomicInteger(200), redirected=new AtomicInteger(), count=new AtomicInteger();
        AtomicBoolean oversized=new AtomicBoolean(), delayed=new AtomicBoolean(), auth=new AtomicBoolean();
        server.createContext("/feed", exchange -> {
            count.incrementAndGet();
            auth.set("Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization")));
            if (delayed.get()) try { Thread.sleep(6500); } catch (InterruptedException ignored) {}
            var snapshot=object().put("authorizationExpiresAt",java.time.Instant.now().plusSeconds(3600).toString());
            snapshot.putArray("offers");snapshot.putObject("capabilities").put("verified",true);
            byte[] data=oversized.get()?new byte[8*1024*1024+1]:snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (status.get()==302) exchange.getResponseHeaders().set("Location","https://localhost:"+port+"/forbidden");
            try { exchange.sendResponseHeaders(status.get(),data.length); exchange.getResponseBody().write(data); }
            catch(java.io.IOException cancelled) {} finally { exchange.close(); }
        });
        server.createContext("/forbidden",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
        server.start();
        try {
            SourceFeed feed=new SourceFeed();
            // Keep the startup scheduler from interleaving with per-request assertions.
            synchronized (feed) {
            feed.refresh();
            check(feed.read().path("offers").isArray(),"HTTPS snapshot was not read");
            check(auth.get(),"supplier authorization header missing");
            for(int code:new int[]{401,403,429,503,302}) {
                status.set(code); int before=count.get(); feed.refresh();
                check(count.get()==before+1,"unexpected supplier retry");
                try { feed.read(); throw new AssertionError("supplier failure used a stale snapshot"); }
                catch(ApiError error) {
                    check(error.status==(code==401||code==403?403:503),"wrong transport status");
                    if(code==429)check(error.code.equals("SOURCE_RATE_LIMITED"),"quota state lost");
                }
            }
            check(redirected.get()==0,"supplier redirect was followed");
            status.set(200); oversized.set(true); feed.refresh();
            try { feed.read(); throw new AssertionError("oversized payload accepted"); } catch(ApiError expected) {}
            oversized.set(false); delayed.set(true);
            long start=System.nanoTime();feed.refresh();
            check((System.nanoTime()-start)/1_000_000<6000,"supplier exceeded its time budget");
            try { feed.read(); throw new AssertionError("timeout kept snapshot available"); } catch(ApiError expected) {}
            delayed.set(false); feed.refresh();check(feed.read().path("offers").isArray(),"source failed to recover");
            }
            System.out.println("HTTPS supplier transport, credentials, failures, quota, redirects, body limits, timeout and recovery passed");
        } catch (Throwable error) { error.printStackTrace();System.exit(1); }
        finally { server.stop(0);executor.shutdownNow(); }
        System.exit(0);
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
