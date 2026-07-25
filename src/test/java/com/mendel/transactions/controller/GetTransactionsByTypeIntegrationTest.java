package com.mendel.transactions.controller;

import com.jayway.jsonpath.JsonPath;
import com.mendel.transactions.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetTransactionsByTypeIntegrationTest extends IntegrationTestBase {

    private void put(long id, String body) {
        rest.exchange("/transactions/" + id, HttpMethod.PUT, json(body), String.class);
    }

    private ResponseEntity<String> getByType(String type) {
        return rest.exchange("/transactions/types/" + type, HttpMethod.GET, null, String.class);
    }

    @Test
    void returnsSingleIdForType() {
        put(10, "{\"amount\": 5000, \"type\": \"cars\"}");

        ResponseEntity<String> response = getByType("cars");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<List<Integer>>read(response.getBody(), "$")).containsExactly(10);
    }

    @Test
    void returnsEmptyArrayForUnknownType() {
        ResponseEntity<String> response = getByType("does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().trim()).isEqualTo("[]");
    }

    @Test
    void returnsAllIdsOfTypeSortedAscending() {
        put(10, "{\"amount\": 5000, \"type\": \"cars\"}");
        put(11, "{\"amount\": 10000, \"type\": \"shopping\", \"parent_id\": 10}");
        put(12, "{\"amount\": 5000, \"type\": \"shopping\", \"parent_id\": 11}");

        ResponseEntity<String> response = getByType("shopping");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Bare JSON array, ids ascending.
        assertThat(response.getBody().trim()).startsWith("[");
        assertThat(JsonPath.<List<Integer>>read(response.getBody(), "$")).containsExactly(11, 12);
    }
}
