package com.mendel.transactions.exception;

/**
 * Thrown when a transaction is well-formed but semantically invalid as a graph
 * operation — a transaction set as its own parent, or a link that would create a
 * cycle. Mapped to HTTP 422 by the global exception handler.
 */
public class InvalidTransactionException extends RuntimeException {

    public InvalidTransactionException(String message) {
        super(message);
    }
}
