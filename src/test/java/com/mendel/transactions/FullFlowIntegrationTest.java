package com.mendel.transactions;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end replay of the worked example from the challenge spec (Codigo 4),
 * exercising all three endpoints over real HTTP with raw JSON bodies.
 */
class FullFlowIntegrationTest extends IntegrationTestBase {

    private ResponseEntity<String> put(long id, String body) {
        return rest.exchange("/transactions/" + id, HttpMethod.PUT, json(body), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, null, String.class);
    }

    private double sumOf(ResponseEntity<String> response) {
        return ((Number) JsonPath.read(response.getBody(), "$.sum")).doubleValue();
    }

    @Test
    void replaysTheSpecExample() {
        // PUT /transactions/10 { "amount": 5000, "type": "cars" } => { "status": "ok" }
        ResponseEntity<String> created = put(10, "{\"amount\": 5000, \"type\": \"cars\"}");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(created.getBody(), "$.status")).isEqualTo("ok");

        // PUT the two linked shopping transactions.
        assertThat(put(11, "{\"amount\": 10000, \"type\": \"shopping\", \"parent_id\": 10}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(12, "{\"amount\": 5000, \"type\": \"shopping\", \"parent_id\": 11}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET /transactions/types/cars => [10]
        assertThat(JsonPath.<List<Integer>>read(get("/transactions/types/cars").getBody(), "$"))
                .containsExactly(10);

        // GET /transactions/sum/10 => {"sum":20000}
        assertThat(sumOf(get("/transactions/sum/10"))).isEqualTo(20000.0);
        // GET /transactions/sum/11 => {"sum":15000}
        assertThat(sumOf(get("/transactions/sum/11"))).isEqualTo(15000.0);
    }
}
