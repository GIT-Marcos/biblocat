package com.biblocat.client;

import java.time.Duration;

public final class HttpUtil {

    public static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    public static boolean isClientError(int status) {
        return status >= 400 && status < 500;
    }

    public static void sleepBackoff(int attempt, int backoffSeconds) {
        var seconds = (long) backoffSeconds * (1L << attempt);
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpUtil() {
    }
}
