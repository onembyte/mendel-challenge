package com.mendel.transactions.dto;

/** Response body of a successful {@code PUT}: {@code {"status":"ok"}}. */
public record StatusResponse(String status) {

    public static StatusResponse ok() {
        return new StatusResponse("ok");
    }
}
