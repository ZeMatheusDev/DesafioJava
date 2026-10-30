package com.desafio.activation.api;

import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.application.CreationResult;
import com.desafio.activation.application.DuplicateRequestException;
import com.desafio.activation.application.NewActivationRequest;
import com.desafio.activation.application.RequestNotFoundException;
import com.desafio.activation.config.SecurityConfig;
import com.desafio.activation.domain.ActivationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfig.class)
class ActivationRequestControllerTest {

    private static final String CLIENT = "sistema-origem";
    private static final String VALID_BODY = """
            {"externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivationRequestService service;

    @Test
    void createsRequestAndReturnsLocation() throws Exception {
        var request = sampleRequest();
        given(service.create(any(NewActivationRequest.class), eq(CLIENT))).willReturn(new CreationResult(request, true));

        mvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/requests/" + request.getId())))
                .andExpect(jsonPath("$.id").value(request.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"));
    }

    @Test
    void retryOfSameRequestReturnsOkWithExistingRecord() throws Exception {
        given(service.create(any(), eq(CLIENT))).willReturn(new CreationResult(sampleRequest(), false));

        mvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void conflictingDuplicateReturns409() throws Exception {
        given(service.create(any(), eq(CLIENT))).willThrow(new DuplicateRequestException("EXT-12345"));

        mvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Solicitação duplicada"));
    }

    @Test
    void rejectsInvalidPayloadWithFieldErrors() throws Exception {
        var body = """
                {"externalId": "EXT 1", "productCode": "%s"}
                """.formatted("X".repeat(65));

        mvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/validation-error"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("é obrigatório"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedJsonWithoutLeakingDetails() throws Exception {
        mvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.detail", not(containsString("Jackson"))));
    }

    @Test
    void returnsRequestById() throws Exception {
        var request = sampleRequest();
        given(service.get(request.getId(), CLIENT)).willReturn(request);

        mvc.perform(get("/api/v1/requests/{id}", request.getId()).with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"));
    }

    @Test
    void unknownRequestReturns404() throws Exception {
        var id = UUID.randomUUID();
        given(service.get(id, CLIENT)).willThrow(new RequestNotFoundException(id));

        mvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", "nao-e-uuid").with(reader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedErrorHidesInternals() throws Exception {
        given(service.create(any(), any())).willThrow(new IllegalStateException("senha do banco: 123"));

        mvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail", not(containsString("senha"))))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void withoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readOnlyScopeCannotCreate() throws Exception {
        mvc.perform(post("/api/v1/requests").with(reader()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void writeOnlyScopeCannotRead() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(writer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void propagatesCorrelationId() throws Exception {
        var request = sampleRequest();
        given(service.get(request.getId(), CLIENT)).willReturn(request);

        mvc.perform(get("/api/v1/requests/{id}", request.getId()).with(reader()).header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    private static RequestPostProcessor writer() {
        return jwt().jwt(token -> token.subject(CLIENT).claim("scope", "activation:write"));
    }

    private static RequestPostProcessor reader() {
        return jwt().jwt(token -> token.subject(CLIENT).claim("scope", "activation:read"));
    }

    private static ActivationRequest sampleRequest() {
        return ActivationRequest.create("EXT-12345", "CUST-10001", "INTERNET-500", CLIENT, null,
                Instant.parse("2026-10-26T14:03:11Z"));
    }
}
