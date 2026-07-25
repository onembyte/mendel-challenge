package com.mendel.transactions.controller;

import com.mendel.transactions.dto.StatusResponse;
import com.mendel.transactions.dto.TransactionRequest;
import com.mendel.transactions.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PutMapping("/{id}")
    public StatusResponse put(@PathVariable long id, @Valid @RequestBody TransactionRequest request) {
        service.createOrUpdate(id, request);
        return StatusResponse.ok();
    }
}
