package com.mendel.transactions.repository;

import com.mendel.transactions.model.Transaction;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage abstraction for transactions (DIP — the service depends on this
 * interface, not the in-memory implementation, so storage can be swapped).
 */
public interface TransactionRepository {

    /**
     * Create or replace a transaction. Enforces the storage invariants that a
     * referenced parent exists and that no transaction is its own parent or part
     * of a cycle, re-indexing type and parent atomically.
     *
     * @throws com.mendel.transactions.exception.TransactionNotFoundException if {@code parentId} references a missing transaction
     * @throws com.mendel.transactions.exception.InvalidTransactionException  if the link is a self-reference or would create a cycle
     */
    void upsert(Transaction transaction);

    Optional<Transaction> findById(long id);

    boolean existsById(long id);

    /** Ids of all transactions of the given type, sorted ascending (empty if none). */
    List<Long> findIdsByType(String type);

    /** Ids of the direct children of the given transaction (empty if none). */
    Set<Long> childrenOf(long id);

    /** Remove all transactions and indexes. Used to reset state between tests. */
    void clear();
}
