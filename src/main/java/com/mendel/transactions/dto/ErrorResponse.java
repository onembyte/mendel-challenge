package com.mendel.transactions.dto;

/** Consistent error body returned by the global exception handler. */
public record ErrorResponse(int status, String error, String message) {
}
