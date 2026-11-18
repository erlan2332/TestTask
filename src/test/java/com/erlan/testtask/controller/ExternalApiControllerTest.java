package com.erlan.testtask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.exception.ExternalServiceException;
import com.erlan.testtask.config.SecurityConfig;
import com.erlan.testtask.exception.GlobalExceptionHandler;
import com.erlan.testtask.logging.HttpLoggingFilter;
import com.erlan.testtask.logging.HttpBodySanitizer;
import com.erlan.testtask.service.ExternalApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExternalApiController.class)
@Import({GlobalExceptionHandler.class, HttpLoggingFilter.class, HttpBodySanitizer.class, SecurityConfig.class})
@ExtendWith(OutputCaptureExtension.class)
@WithMockUser(username = "test-user")
class ExternalApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ExternalApiService service;

    @Test
    void returnsExternalJsonWithoutFilterChangingBody(CapturedOutput output) throws Exception {
        String json = "[{\"id\":\"1\",\"name\":\"Pixel\",\"data\":null}]";
        when(service.getObjects()).thenReturn(objectMapper.readTree(json));

        var response = mockMvc.perform(get("/api/external/objects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Pixel"))
                .andReturn().getResponse();

        assertThat(objectMapper.readTree(response.getContentAsString())).isEqualTo(objectMapper.readTree(json));
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(output.getOut()).contains("HTTP REQUEST | GET /api/external/objects | body=[omitted]");
        assertThat(output.getOut()).contains("HTTP RESPONSE | GET /api/external/objects | status=200 | durationMs=");
        assertThat(output.getOut()).doesNotContain("response=" + json);
    }

    @Test
    void externalFailureReturnsStructured502WithoutInternalDetails() throws Exception {
        when(service.getObjects()).thenThrow(new ExternalServiceException(
                "External service unavailable", new IllegalStateException("private internal detail")));

        var response = mockMvc.perform(get("/api/external/objects"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.message").value("External service unavailable"))
                .andExpect(jsonPath("$.path").value("/api/external/objects"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("private internal detail", "IllegalStateException");
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
    }
}
