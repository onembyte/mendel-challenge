package com.mendel.transactions.service;

import com.mendel.transactions.dto.TransactionRequest;
import com.mendel.transactions.model.Transaction;
import com.mendel.transactions.repository.TransactionRepository;
import org.springframework.stereotype.Service;

/**
 * Application service: maps requests to the domain model and delegates storage
 * and graph invariants to the repository.
 */
@Service
public class TransactionService {

    private final TransactionRepository repository;

    public TransactionService(TransactionRepository repository) {
        this.repository = repository;
    }

    /**
     * Create the transaction with the given id, or replace it if it already
     * exists (PUT is an upsert). {@code amount} is guaranteed non-null by request
     * validation, so unboxing here is safe.
     */
    public void createOrUpdate(long id, TransactionRequest request) {
        Transaction transaction =
                new Transaction(id, request.amount(), request.type(), request.parentId());
        repository.upsert(transaction);
    }
}
