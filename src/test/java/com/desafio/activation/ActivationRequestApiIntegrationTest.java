package com.desafio.activation;

import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.application.CreationResult;
import com.desafio.activation.application.DuplicateRequestException;
import com.desafio.activation.application.NewActivationRequest;
import com.desafio.activation.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActivationRequestApiIntegrationTest extends IntegrationTest {

    private static final String CLIENT_A = "sistema-a";
    private static final String CLIENT_B = "sistema-b";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ActivationRequestService service;

    @Test
    void createsAndFetchesRequest() throws Exception {
        var response = mvc.perform(post("/api/v1/requests").with(client(CLIENT_A))
                        .contentType(MediaType.APPLICATION_JSON).content(body("EXT-1", "INTERNET-500")))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        String id = JsonPath.read(response.getContentAsString(), "$.id");

        mvc.perform(get(response.getHeader("Location")).with(client(CLIENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:00:00Z"));
    }

    @Test
    void retryWithSameContentReturnsExistingRecord() throws Exception {
        String first = createAndGetId(CLIENT_A, "EXT-1", "INTERNET-500");

        mvc.perform(post("/api/v1/requests").with(client(CLIENT_A))
                        .contentType(MediaType.APPLICATION_JSON).content(body("EXT-1", "INTERNET-500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentContentIsRejected() throws Exception {
        createAndGetId(CLIENT_A, "EXT-1", "INTERNET-500");

        mvc.perform(post("/api/v1/requests").with(client(CLIENT_A))
                        .contentType(MediaType.APPLICATION_JSON).content(body("EXT-1", "TV-HD")))
                .andExpect(status().isConflict());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithSameExternalIdCreateExactlyOneRecord() throws Exception {
        int threads = 16;
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        Callable<CreationResult> task = () -> {
            ready.countDown();
            start.await();
            return service.create(new NewActivationRequest("EXT-RACE", "CUST-1", "INTERNET-500"), CLIENT_A);
        };

        List<CreationResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<CreationResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(task));
            }
            ready.await();
            start.countDown();
            for (Future<CreationResult> future : futures) {
                results.add(future.get());
            }
        }

        assertThat(repository.count()).isEqualTo(1);
        assertThat(results).filteredOn(CreationResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.request().getId()).containsOnly(results.getFirst().request().getId());
    }

    @Test
    void consumerCannotSeeRequestsFromAnotherConsumer() throws Exception {
        String id = createAndGetId(CLIENT_A, "EXT-1", "INTERNET-500");

        mvc.perform(get("/api/v1/requests/{id}", id).with(client(CLIENT_B)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requests").with(client(CLIENT_B)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anotherConsumerReusingExternalIdGetsConflict() {
        service.create(new NewActivationRequest("EXT-1", "CUST-1", "INTERNET-500"), CLIENT_A);

        assertThatThrownBy(() ->
                        service.create(new NewActivationRequest("EXT-1", "CUST-1", "INTERNET-500"), CLIENT_B))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void unknownIdReturns404() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(client(CLIENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsWithFiltersAndPagination() throws Exception {
        createAndGetId(CLIENT_A, "EXT-1", "INTERNET-500");
        createAndGetId(CLIENT_A, "EXT-2", "TV-HD");
        createAndGetId(CLIENT_A, "EXT-3", "TV-HD");

        mvc.perform(get("/api/v1/requests").param("status", "PENDING").param("size", "2").with(client(CLIENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/requests").param("status", "COMPLETED").with(client(CLIENT_A)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/requests").param("status", "INVALIDO").with(client(CLIENT_A)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/requests").param("size", "500").with(client(CLIENT_A)))
                .andExpect(status().isBadRequest());
    }

    private String createAndGetId(String clientId, String externalId, String productCode) throws Exception {
        String json = mvc.perform(post("/api/v1/requests").with(client(clientId))
                        .contentType(MediaType.APPLICATION_JSON).content(body(externalId, productCode)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private static String body(String externalId, String productCode) {
        return """
                {"externalId": "%s", "customerId": "CUST-1", "productCode": "%s"}
                """.formatted(externalId, productCode);
    }

    private static RequestPostProcessor client(String clientId) {
        return jwt().jwt(token -> token.subject(clientId).claim("scope", "activation:read activation:write"));
    }
}
