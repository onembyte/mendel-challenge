package com.mendel.transactions.controller;

import com.jayway.jsonpath.JsonPath;
import com.mendel.transactions.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PutTransactionIntegrationTest extends IntegrationTestBase {

    private ResponseEntity<String> put(long id, String body) {
        return rest.exchange("/transactions/" + id, HttpMethod.PUT, json(body), String.class);
    }

    @Test
    void createReturnsOkStatus() {
        ResponseEntity<String> response = put(10, "{\"amount\": 5000, \"type\": \"cars\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("ok");
        assertThat(repository.findById(10L)).isPresent();
    }

    @Test
    void bindsSnakeCaseParentIdFromRawJson() {
        put(10, "{\"amount\": 5000, \"type\": \"cars\"}");

        ResponseEntity<String> response = put(11, "{\"amount\": 10000, \"type\": \"shopping\", \"parent_id\": 10}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The parent link only binds if "parent_id" maps to the parentId field.
        assertThat(repository.findById(11L).orElseThrow().parentId()).isEqualTo(10L);
        assertThat(repository.childrenOf(10L)).containsExactly(11L);
    }

    @Test
    void updateReindexesTypeAndParent() {
        put(20, "{\"amount\": 100, \"type\": \"cars\"}");
        put(21, "{\"amount\": 100, \"type\": \"cars\"}");
        put(22, "{\"amount\": 50, \"type\": \"food\", \"parent_id\": 20}");

        // Move 22: type food -> travel, parent 20 -> 21.
        ResponseEntity<String> response = put(22, "{\"amount\": 50, \"type\": \"travel\", \"parent_id\": 21}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repository.findIdsByType("food")).doesNotContain(22L);
        assertThat(repository.findIdsByType("travel")).containsExactly(22L);
        assertThat(repository.childrenOf(20L)).doesNotContain(22L);
        assertThat(repository.childrenOf(21L)).containsExactly(22L);
    }

    @Test
    void missingParentReturnsNotFound() {
        ResponseEntity<String> response = put(1, "{\"amount\": 100, \"type\": \"cars\", \"parent_id\": 999}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingAmountReturnsBadRequest() {
        ResponseEntity<String> response = put(1, "{\"type\": \"cars\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankTypeReturnsBadRequest() {
        ResponseEntity<String> response = put(1, "{\"amount\": 100, \"type\": \"\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void selfParentViaUpdateReturnsUnprocessableEntity() {
        put(1, "{\"amount\": 100, \"type\": \"cars\"}");

        ResponseEntity<String> response = put(1, "{\"amount\": 100, \"type\": \"cars\", \"parent_id\": 1}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void cycleViaUpdateReturnsUnprocessableEntityAndLeavesGraphUnchanged() {
        put(10, "{\"amount\": 5000, \"type\": \"cars\"}");
        put(11, "{\"amount\": 10000, \"type\": \"shopping\", \"parent_id\": 10}");
        put(12, "{\"amount\": 5000, \"type\": \"shopping\", \"parent_id\": 11}");

        // Re-point 10 under its own descendant 12 -> would create a cycle.
        ResponseEntity<String> response = put(10, "{\"amount\": 5000, \"type\": \"cars\", \"parent_id\": 12}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // The rejected update must not have mutated the stored graph.
        assertThat(repository.findById(10L).orElseThrow().parentId()).isNull();
    }
}
