package com.mendel.transactions.repository;

import com.mendel.transactions.model.Transaction;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    @Override
    public void upsert(Transaction transaction) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Optional<Transaction> findById(long id) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean existsById(long id) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public List<Long> findIdsByType(String type) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Set<Long> childrenOf(long id) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
