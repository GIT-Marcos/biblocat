package com.biblocat.client;

import com.biblocat.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.LENIENT)
class ApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private HttpClient httpClient;

    private ApiClient apiClient;

    @BeforeEach
    void setUp() {
        var config = new AgentConfig(
                null, 0, 10, 30, 30, 500, 3,
                50, 1, 2, 5, BASE_URL
        );
        apiClient = new ApiClient(httpClient, config);
    }

    @Test
    void getPaths_returnsDeserializedList() throws Exception {
        doReturn(response(200, """
                [{"id":"s1","path":"a.pdf","contentHash":"abc","pathLower":"a.pdf","deletedAt":null}]
                """))
                .when(httpClient).send(any(HttpRequest.class), any());

        var result = apiClient.getPaths();

        assertEquals(1, result.size());
        assertEquals("s1", result.getFirst().id());
    }

    @Test
    void getPaths_retriesOnServerError() throws Exception {
        doReturn(response(500, ""))
                .when(httpClient).send(any(HttpRequest.class), any());

        assertThrows(ApiException.class, () -> apiClient.getPaths());

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void getPaths_retriesOnIOException() throws Exception {
        doThrow(new IOException("Connection refused"))
                .when(httpClient).send(any(HttpRequest.class), any());

        assertThrows(ApiException.class, () -> apiClient.getPaths());

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void getPending_returnsTrue() throws Exception {
        doReturn(response(200, "{\"pending\":true}"))
                .when(httpClient).send(any(HttpRequest.class), any());

        var result = apiClient.getPending();

        assertTrue(result.pending());
    }

    @Test
    void getPending_returnsFalseOnFailure() throws Exception {
        doThrow(new IOException("Timeout"))
                .when(httpClient).send(any(HttpRequest.class), any());

        var result = apiClient.getPending();

        assertFalse(result.pending());
    }

    @Test
    void postAck_success() throws Exception {
        doReturn(response(200, "{\"acknowledged\":true}"))
                .when(httpClient).send(any(HttpRequest.class), any());

        var result = apiClient.postAck();

        assertTrue(result.acknowledged());
    }

    @Test
    void postAck_logsWarningOnFailure() throws Exception {
        doThrow(new IOException("Timeout"))
                .when(httpClient).send(any(HttpRequest.class), any());

        var result = apiClient.postAck();

        assertFalse(result.acknowledged());
    }

    @Test
    void getPending_returnsFalseOnNonSuccess() throws Exception {
        doReturn(response(500, ""))
                .when(httpClient).send(any(HttpRequest.class), any());

        var result = apiClient.getPending();

        assertFalse(result.pending());
    }

    @Test
    void checkConnectivity_succeedsOnHealthyApi() throws Exception {
        doReturn(response(200, "{\"pending\":false}"))
                .when(httpClient).send(any(HttpRequest.class), any());

        assertDoesNotThrow(() -> apiClient.checkConnectivity());
    }

    @Test
    void checkConnectivity_throwsOnApiUnreachable() throws Exception {
        doThrow(new IOException("Connection refused"))
                .when(httpClient).send(any(HttpRequest.class), any());

        assertThrows(ApiException.class, () -> apiClient.checkConnectivity());

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static HttpResponse<String> response(int status, String body) {
        var resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }
}
