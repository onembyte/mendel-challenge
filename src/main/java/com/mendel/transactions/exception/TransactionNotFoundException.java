package com.mendel.transactions.exception;

/**
 * Thrown when a transaction referenced by id does not exist — either a
 * {@code parent_id} that points at nothing on write, or a lookup (sum) of an
 * unknown id. Mapped to HTTP 404 by the global exception handler.
 */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(long id) {
        super("Transaction not found: " + id);
    }
}
