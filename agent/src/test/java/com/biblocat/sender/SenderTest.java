package com.biblocat.sender;

import com.biblocat.config.AgentConfig;
import com.biblocat.dto.Operation;
import com.biblocat.model.FileFormat;
import com.biblocat.model.OperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.LENIENT)
class SenderTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private HttpClient httpClient;

    private Sender sender;

    @BeforeEach
    void setUp() {
        var config = new AgentConfig(
                null, 0, 10, 30, 30, 500, 3,
                50, 1, 2, 5, BASE_URL
        );
        sender = new Sender(httpClient, config);
    }

    @Test
    void sendsBatchSuccessfully() throws Exception {
        doReturn(response(200, """
                {"processed":2,"created":1,"renamed":0,"updated":0,"deleted":1,"reactivated":0,"errors":[]}
                """))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(2)));

        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void logsIndividualErrorsInResponse() throws Exception {
        doReturn(response(200, """
                {"processed":1,"created":1,"renamed":0,"updated":0,"deleted":0,"reactivated":0,"errors":[{"type":"CREATE","path":"bad.pdf","error":"UNSUPPORTED_FORMAT"}]}
                """))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void duplicatePathRetriesOnce() throws Exception {
        doReturn(
                response(200, """
                        {"processed":1,"created":1,"renamed":0,"updated":0,"deleted":0,"reactivated":0,"errors":[{"type":"CREATE","path":"dup.pdf","error":"DUPLICATE_PATH"}]}
                        """),
                response(200, """
                        {"processed":1,"created":1,"renamed":0,"updated":0,"deleted":0,"reactivated":0,"errors":[]}
                        """)
        ).when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void http409RetriesOnce() throws Exception {
        doReturn(
                response(409, ""),
                response(200, """
                        {"processed":1,"created":1,"renamed":0,"updated":0,"deleted":0,"reactivated":0,"errors":[]}
                        """)
        ).when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void http409RetryFails_abandonsBatch() throws Exception {
        doReturn(response(409, ""))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void http4xxAbandonsImmediately() throws Exception {
        doReturn(response(400, "Bad request"))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void http5xxRetriesThenAbandons() throws Exception {
        doReturn(response(503, ""))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void sendsToCorrectEndpoint() throws Exception {
        doReturn(response(200, """
                {"processed":1,"created":1,"renamed":0,"updated":0,"deleted":0,"reactivated":0,"errors":[]}
                """))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());

        var request = captor.getValue();
        assertEquals(BASE_URL + "/api/sources/reconcile", request.uri().toString());
        assertEquals("POST", request.method());
        assertTrue(request.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    void sendsJsonBody() throws Exception {
        doReturn(response(200, """
                {"processed":1,"created":1,"renamed":0,"updated":0,"deleted":0,"reactivated":0,"errors":[]}
                """))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());

        var bodyPublisher = captor.getValue().bodyPublisher();
        assertTrue(bodyPublisher.isPresent());
    }

    @Test
    void emptyBatch_doesNothing() throws Exception {
        sender.send(List.of());

        verify(httpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void nullBatches_doesNothing() throws Exception {
        sender.send(null);

        verify(httpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void ioExceptionRetriesThenAbandons() throws Exception {
        doThrow(new IOException("Connection reset"))
                .when(httpClient).send(any(HttpRequest.class), any());

        sender.send(List.of(sampleBatch(1)));

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    private static List<Operation> sampleBatch(int count) {
        return java.util.stream.Stream.generate(() -> new Operation(
                OperationType.CREATE, null, "test.pdf",
                "author/test.pdf", "author/test.pdf",
                "abc", FileFormat.PDF, "author"
        )).limit(count).toList();
    }

    @SuppressWarnings("rawtypes")
    private static HttpResponse<String> response(int status, String body) {
        var resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }
}
