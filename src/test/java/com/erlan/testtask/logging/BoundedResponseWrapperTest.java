package com.erlan.testtask.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class BoundedResponseWrapperTest {

    @Test
    void streamsLargeResponseWhileCapturingOnlyTheLimit() throws Exception {
        MockHttpServletResponse client = new MockHttpServletResponse();
        var response = new BoundedResponseWrapper(client, 8192);
        byte[] body = "x".repeat(1_000_000).getBytes(StandardCharsets.UTF_8);

        response.getOutputStream().write(body);
        response.flushCapture();

        assertThat(client.getContentAsByteArray()).isEqualTo(body);
        assertThat(response.getCapturedContent()).hasSize(8192);
    }

    @Test
    void writerContentAlsoReachesClientAndCapture() throws Exception {
        MockHttpServletResponse client = new MockHttpServletResponse();
        client.setCharacterEncoding("UTF-8");
        var response = new BoundedResponseWrapper(client, 8192);

        response.getWriter().write("Задача создана");
        response.flushCapture();

        assertThat(client.getContentAsString()).isEqualTo("Задача создана");
        assertThat(new String(response.getCapturedContent(), StandardCharsets.UTF_8))
                .isEqualTo("Задача создана");
    }
}
