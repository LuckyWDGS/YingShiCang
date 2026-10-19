package com.fongmi.android.tv.api;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SiteApiResponseTest {
    private static final Request REQUEST = new Request.Builder().url("http://127.0.0.1/source").build();

    @Test public void bodyMustBeSuccessfulPresentAndNonBlank() throws Exception {
        assertEquals("{\"list\":[]}", SiteApi.readRequiredBody(response(200, "{\"list\":[]}"), "unavailable", "empty"));
        assertFailure(response(503, "error"), "unavailable");
        assertFailure(response(200, "  \n\t"), "empty");
    }

    private static Response response(int code, String body) {
        return new Response.Builder().request(REQUEST).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body == null ? null : ResponseBody.create(body, null)).build();
    }

    private static void assertFailure(Response response, String message) throws IOException {
        try (response) {
            SiteApi.readRequiredBody(response, "unavailable", "empty");
            fail("Accepted invalid source response");
        } catch (IOException expected) {
            assertEquals(message, expected.getMessage());
        }
    }
}
