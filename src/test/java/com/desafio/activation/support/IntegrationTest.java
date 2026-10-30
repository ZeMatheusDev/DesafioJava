package com.desafio.activation.support;

import com.desafio.activation.domain.ActivationRequestRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base dos testes de integração: PostgreSQL real (Testcontainers), provedor simulado (WireMock)
 * e relógio controlável. O scheduler fica desligado; os testes chamam o processador diretamente.
 * Banco e WireMock sobem uma vez só e são compartilhados, para o contexto Spring ser reaproveitado.
 */
@SpringBootTest(properties = {
        "activation.processing.scheduler-enabled=false",
        "activation.processing.max-attempts=3",
        "activation.provider.read-timeout=500ms"
})
@AutoConfigureMockMvc
@Import(IntegrationTest.ClockTestConfig.class)
public abstract class IntegrationTest {

    protected static final Instant START = Instant.parse("2026-10-26T14:00:00Z");

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    protected static final WireMockServer provider = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        POSTGRES.start();
        provider.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("activation.provider.base-url", provider::baseUrl);
    }

    @Autowired
    protected ActivationRequestRepository repository;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        repository.deleteAll();
        provider.resetAll();
        clock.set(START);
    }

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }
}
