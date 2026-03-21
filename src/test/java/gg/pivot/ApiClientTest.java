package gg.pivot;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiClient retry/backoff behaviour.
 */
public class ApiClientTest {

    private PivotPlugin plugin;
    private FileConfiguration config;
    private OkHttpClient httpClient;
    private ApiClient apiClient;

    /** Helper to build a minimal okhttp Response. */
    private static Response buildResponse(Request request, int code, String body, String... headers) {
        Response.Builder builder = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("mock")
                .body(ResponseBody.create(body, MediaType.parse("application/json")));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.addHeader(headers[i], headers[i + 1]);
        }
        return builder.build();
    }

    /** Helper to build a dummy Request matching the one ApiClient would build. */
    private static Request dummyRequest() {
        return new Request.Builder()
                .url("https://api.pivotmc.dev/v1/ingest")
                .addHeader("X-API-Key", "pvt_testapikey123456789")
                .post(okhttp3.RequestBody.create("{}", MediaType.parse("application/json")))
                .build();
    }

    @BeforeEach
    public void setup() {
        plugin = mock(PivotPlugin.class);
        config = mock(FileConfiguration.class);
        httpClient = mock(OkHttpClient.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getApiKey()).thenReturn("pvt_testapikey123456789");
        when(plugin.getApiEndpoint()).thenReturn("https://api.pivotmc.dev/v1/ingest");
        when(plugin.isEnabled()).thenReturn(true);
        when(config.getBoolean("debug.enabled", false)).thenReturn(false);

        apiClient = new ApiClient(plugin, httpClient);
    }

    /**
     * On a network failure the client should schedule one retry attempt
     * with the first backoff delay (5 s = 100 ticks).
     */
    @Test
    public void testNetworkFailureSchedulesRetry() throws Exception {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(mockCall);

        doAnswer(invocation -> {
            Callback cb = invocation.getArgument(0);
            cb.onFailure(mockCall, new IOException("connection refused"));
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), eq(100L)))
                .thenReturn(mock(BukkitTask.class));

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            apiClient.sendToAPI("{\"test\":true}");

            verify(scheduler).runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), eq(100L));
        }
    }

    /**
     * After BACKOFF_SECONDS.length (4) retry attempts, the client should
     * discard the batch instead of scheduling another retry.
     */
    @Test
    public void testMaxRetriesExceeded_discardsBatch() throws Exception {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(mockCall);

        doAnswer(invocation -> {
            Callback cb = invocation.getArgument(0);
            cb.onFailure(mockCall, new IOException("still failing"));
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            // Invoke the private sendToAPI(json, retryCount) with retryCount at max
            Method sendWithRetry = ApiClient.class.getDeclaredMethod("sendToAPI", String.class, int.class);
            sendWithRetry.setAccessible(true);
            // BACKOFF_SECONDS.length == 4, so retryCount=4 means max exceeded
            sendWithRetry.invoke(apiClient, "{\"test\":true}", 4);

            // scheduleRetry should not schedule anything
            verify(scheduler, never()).runTaskLaterAsynchronously(any(), any(Runnable.class), anyLong());
        }
    }

    /**
     * A 429 response with a Retry-After header should schedule a retry with
     * retryCount+1 and the header-specified delay (30 s = 600 ticks).
     */
    @Test
    public void testRateLimitResponse_schedulesRetryWithCorrectCount() throws Exception {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(mockCall);

        doAnswer(invocation -> {
            Callback cb = invocation.getArgument(0);
            cb.onResponse(mockCall, buildResponse(dummyRequest(), 429, "{}", "Retry-After", "30"));
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), eq(600L)))
                .thenReturn(mock(BukkitTask.class));

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            // Start with retryCount=0; should schedule with nextRetryCount=1 and 30s delay
            apiClient.sendToAPI("{\"test\":true}");

            verify(scheduler).runTaskLaterAsynchronously(eq(plugin), any(Runnable.class), eq(600L));
        }
    }

    /**
     * A 429 response at the maximum retry count should discard the batch
     * rather than retrying indefinitely.
     */
    @Test
    public void testRateLimitAtMaxRetries_discardsBatch() throws Exception {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(mockCall);

        doAnswer(invocation -> {
            Callback cb = invocation.getArgument(0);
            cb.onResponse(mockCall, buildResponse(dummyRequest(), 429, "{}", "Retry-After", "30"));
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            Method sendWithRetry = ApiClient.class.getDeclaredMethod("sendToAPI", String.class, int.class);
            sendWithRetry.setAccessible(true);
            // retryCount=4 == BACKOFF_SECONDS.length; 429 branch should discard
            sendWithRetry.invoke(apiClient, "{\"test\":true}", 4);

            verify(scheduler, never()).runTaskLaterAsynchronously(any(), any(Runnable.class), anyLong());
        }
    }

    /**
     * A 401 response must NOT schedule any retry.
     */
    @Test
    public void testUnauthorizedResponse_noRetry() throws Exception {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(mockCall);

        doAnswer(invocation -> {
            Callback cb = invocation.getArgument(0);
            cb.onResponse(mockCall, buildResponse(dummyRequest(), 401, "Unauthorized"));
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            apiClient.sendToAPI("{\"test\":true}");

            verify(scheduler, never()).runTaskLaterAsynchronously(any(), any(Runnable.class), anyLong());
        }
    }

    /**
     * A 400 response must NOT schedule any retry.
     */
    @Test
    public void testBadRequestResponse_noRetry() throws Exception {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(mockCall);

        doAnswer(invocation -> {
            Callback cb = invocation.getArgument(0);
            cb.onResponse(mockCall, buildResponse(dummyRequest(), 400, "Bad Request"));
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            apiClient.sendToAPI("{\"test\":true}");

            verify(scheduler, never()).runTaskLaterAsynchronously(any(), any(Runnable.class), anyLong());
        }
    }
}
