package gg.pivot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Timeout;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Batch id and the shutdown path of {@link EventCollector}. */
public class EventCollectorBatchTest {
    private PivotPlugin plugin;
    private FileConfiguration config;
    private OkHttpClient httpClient;
    private Call call;
    private EventCollector collector;

    @BeforeEach
    public void setup() throws IOException {
        plugin = mock(PivotPlugin.class);
        config = mock(FileConfiguration.class);
        httpClient = mock(OkHttpClient.class);
        call = mock(Call.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenReturn("pvt_validkey1234567890");
        when(plugin.getApiEndpoint()).thenReturn("https://api.example.com/v1/ingest");
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.timeout()).thenReturn(new Timeout());
        when(call.execute()).thenAnswer(inv -> okResponse());

        collector = new EventCollector(plugin, httpClient);
    }

    private static Response okResponse() {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.example.com/v1/ingest").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(ResponseBody.create("{}", MediaType.parse("application/json")))
                .build();
    }

    private static JsonObject bodyOf(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
    }

    @Test
    public void testEachBatchCarriesItsOwnUuidBatchId() throws Exception {
        ArgumentCaptor<Request> requests = ArgumentCaptor.forClass(Request.class);
        when(httpClient.newCall(requests.capture())).thenReturn(call);

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            collector.addPerformanceEvent(20.0, i);
            collector.flush();
        }

        assertEquals(3, requests.getAllValues().size());
        for (Request request : requests.getAllValues()) {
            String id = bodyOf(request).get("batch_id").getAsString();
            assertEquals(id, UUID.fromString(id).toString(), "batch_id must be a canonical UUID");
            ids.add(id);
        }
        assertEquals(3, ids.size(), "each flush is a new batch");
    }

    @Test
    public void testFlushBlockingSendsSynchronouslyWithTheGivenTimeout() throws Exception {
        Timeout timeout = new Timeout();
        when(call.timeout()).thenReturn(timeout);
        collector.addPerformanceEvent(19.0, 3);

        collector.flushBlocking(2000L);

        verify(call).execute();
        verify(call, never()).enqueue(any(Callback.class));
        assertEquals(2_000_000_000L, timeout.timeoutNanos());
    }

    @Test
    public void testFlushBlockingWithNoTimeLeftDropsTheBatchWithoutSending() {
        collector.addPerformanceEvent(19.0, 3);

        collector.flushBlocking(0L);

        verify(httpClient, never()).newCall(any(Request.class));
    }

    @Test
    public void testFlushBlockingWithNothingQueuedSendsNothing() {
        collector.flushBlocking(2000L);

        verify(httpClient, never()).newCall(any(Request.class));
    }

    @Test
    public void testFlushBlockingSwallowsATimeout() throws Exception {
        when(call.execute()).thenThrow(new java.io.InterruptedIOException("timeout"));
        collector.addPerformanceEvent(19.0, 3);

        assertDoesNotThrow(() -> collector.flushBlocking(1000L));
    }

    @Test
    public void testServerStopUsesTheRemainingBudget() throws Exception {
        Timeout timeout = new Timeout();
        when(call.timeout()).thenReturn(timeout);

        collector.sendServerStopEvent("manual", 1500L);

        verify(call).execute();
        assertEquals(1_500_000_000L, timeout.timeoutNanos());
    }

    @Test
    public void testServerStopWithNoTimeLeftIsSkipped() {
        collector.sendServerStopEvent("manual", 0L);

        verify(httpClient, never()).newCall(any(Request.class));
    }

    @Test
    public void testShutdownBudgetIsAtMostThreeSeconds() {
        assertTrue(PivotPlugin.SHUTDOWN_BUDGET_MS <= 3000L);
    }

    @Test
    public void testShutdownReleasesTheHttpClient() {
        OkHttpClient real = new OkHttpClient();
        EventCollector withRealClient = new EventCollector(plugin, real);

        withRealClient.shutdown();

        assertTrue(real.dispatcher().executorService().isShutdown());
    }
}
