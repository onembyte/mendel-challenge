package com.mendel.transactions.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Incoming body of {@code PUT /transactions/{id}}.
 *
 * <p>Wrapper types are deliberate: a primitive {@code double amount} would
 * deserialize a missing field to {@code 0.0} and make {@code @NotNull}
 * unenforceable, and a primitive {@code long parentId} would turn every
 * parentless request into a link to transaction 0. The wire key is the spec's
 * snake_case {@code parent_id}; only this field deviates from the domain's
 * camelCase, so it is mapped explicitly rather than via a global naming strategy.
 */
public record TransactionRequest(

        @NotNull Double amount,

        @NotBlank String type,

        @JsonProperty("parent_id") Long parentId) {
}
