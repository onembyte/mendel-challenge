package com.mendel.transactions.dto;

/**
 * Response body of {@code GET /transactions/sum/{id}}: {@code {"sum": <double>}}.
 *
 * <p>The spec types {@code sum} as a double, so a whole-number total renders as
 * e.g. {@code 20000.0}; that is numerically equal to the spec's {@code 20000}.
 */
public record SumResponse(double sum) {
}
