package com.mendel.transactions;

import com.mendel.transactions.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Shared base for HTTP integration tests. Carries the single
 * {@code @SpringBootTest(RANDOM_PORT)} configuration so Spring's context cache
 * boots the server once for the whole suite, while {@link #resetState()} gives
 * every test method an empty in-memory store.
 *
 * <p>{@code @AutoConfigureTestRestTemplate} is required under Spring Boot 4:
 * unlike Boot 3, the {@link TestRestTemplate} bean is no longer registered by
 * {@code @SpringBootTest} alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class IntegrationTestBase {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected TransactionRepository repository;

    @BeforeEach
    void resetState() {
        repository.clear();
    }

    /** Wrap a raw JSON string as an {@code application/json} request entity. */
    protected static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
