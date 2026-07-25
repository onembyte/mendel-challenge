package com.mendel.transactions.controller;

import com.mendel.transactions.dto.StatusResponse;
import com.mendel.transactions.dto.TransactionRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    @PutMapping("/{id}")
    public StatusResponse put(@PathVariable long id, @Valid @RequestBody TransactionRequest request) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
