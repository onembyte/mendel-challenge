package com.mendel.transactions.controller;

import com.jayway.jsonpath.JsonPath;
import com.mendel.transactions.IntegrationTestBase;
import com.mendel.transactions.model.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GetSumIntegrationTest extends IntegrationTestBase {

    private void put(long id, String body) {
        rest.exchange("/transactions/" + id, HttpMethod.PUT, json(body), String.class);
    }

    private ResponseEntity<String> getSum(long id) {
        return rest.exchange("/transactions/sum/" + id, HttpMethod.GET, null, String.class);
    }

    private double sumOf(ResponseEntity<String> response) {
        return ((Number) JsonPath.read(response.getBody(), "$.sum")).doubleValue();
    }

    @Test
    void sumsTransactionAndAllDescendants_specExample() {
        put(10, "{\"amount\": 5000, \"type\": \"cars\"}");
        put(11, "{\"amount\": 10000, \"type\": \"shopping\", \"parent_id\": 10}");
        put(12, "{\"amount\": 5000, \"type\": \"shopping\", \"parent_id\": 11}");

        // Values from the spec's worked example. sum/10 == 20000 also proves the
        // snake_case parent_id links bound end-to-end over HTTP.
        assertThat(sumOf(getSum(10))).isEqualTo(20000.0);
        assertThat(sumOf(getSum(11))).isEqualTo(15000.0);
        assertThat(sumOf(getSum(12))).isEqualTo(5000.0);
    }

    @Test
    void unknownIdReturnsNotFound() {
        ResponseEntity<String> response = getSum(404);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sumsAcrossABranchingTree() {
        // 1 -> {2, 3}, 2 -> {4}. Distinct power-of-two amounts make any wrong
        // traversal produce a distinguishable total, and this is the only shape
        // that exercises a node with more than one child.
        put(1, "{\"amount\": 100, \"type\": \"t\"}");
        put(2, "{\"amount\": 200, \"type\": \"t\", \"parent_id\": 1}");
        put(3, "{\"amount\": 400, \"type\": \"t\", \"parent_id\": 1}");
        put(4, "{\"amount\": 800, \"type\": \"t\", \"parent_id\": 2}");

        assertThat(sumOf(getSum(1))).isEqualTo(1500.0); // 100+200+400+800
        assertThat(sumOf(getSum(2))).isEqualTo(1000.0); // 200+800
        assertThat(sumOf(getSum(3))).isEqualTo(400.0);
    }

    @Test
    void sumsADeepChainWithoutStackOverflow() {
        // Built directly through the repository so the chain can be deep enough
        // (10k) that a recursive traversal would overflow; an iterative one must not.
        int depth = 10_000;
        for (int i = 1; i <= depth; i++) {
            Long parent = (i == 1) ? null : (long) (i - 1);
            repository.upsert(new Transaction(i, 1.0, "chain", parent));
        }

        assertThat(sumOf(getSum(1))).isEqualTo((double) depth);
    }
}
