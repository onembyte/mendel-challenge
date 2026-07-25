package com.mendel.transactions.model;

import java.util.Objects;

/**
 * Immutable transaction stored by the service.
 *
 * <p>{@code parentId} is nullable: a transaction with no parent is a root. The
 * wire contract exposes it as {@code parent_id} (see the request DTO); the domain
 * model stays camelCase.
 */
public record Transaction(long id, double amount, String type, Long parentId) {

    public Transaction {
        Objects.requireNonNull(type, "type must not be null");
    }
}
