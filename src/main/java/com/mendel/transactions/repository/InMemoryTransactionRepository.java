package com.mendel.transactions.repository;

import com.mendel.transactions.exception.InvalidTransactionException;
import com.mendel.transactions.exception.TransactionNotFoundException;
import com.mendel.transactions.model.Transaction;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link TransactionRepository} backed by concurrent maps.
 *
 * <p>Two secondary indexes are maintained alongside the primary store so the
 * read endpoints stay cheap: {@code typeIndex} (type &rarr; ids) answers "list by
 * type" directly, and {@code childrenIndex} (parent id &rarr; child ids) lets the
 * sum traversal walk only a transaction's subtree instead of scanning everything.
 *
 * <p><strong>Concurrency.</strong> A {@link ConcurrentHashMap} is atomic per
 * operation but not across the three maps, and the parent-exists / cycle checks
 * are check-then-act. All writes therefore go through the single
 * {@code synchronized} {@link #upsert} so an update re-indexes atomically and the
 * invariants cannot be raced. Reads stay lock-free on the concurrent structures;
 * a read may transiently observe an in-flight update, but every completed write
 * leaves the store consistent.
 */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<Long, Transaction> store = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> typeIndex = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> childrenIndex = new ConcurrentHashMap<>();

    @Override
    public synchronized void upsert(Transaction transaction) {
        long id = transaction.id();
        Long parentId = transaction.parentId();
        if (parentId != null) {
            requireLinkable(id, parentId);
        }

        Transaction previous = store.get(id);
        if (previous != null) {
            removeFromIndexes(previous);
        }
        typeIndex.computeIfAbsent(transaction.type(), t -> ConcurrentHashMap.newKeySet()).add(id);
        if (parentId != null) {
            childrenIndex.computeIfAbsent(parentId, p -> ConcurrentHashMap.newKeySet()).add(id);
        }
        store.put(id, transaction);
    }

    /**
     * Validate that {@code id} may point at {@code parentId}: the parent must
     * exist, must not be {@code id} itself, and must not already have {@code id}
     * among its ancestors (which would close a cycle). Runs inside the write lock.
     */
    private void requireLinkable(long id, long parentId) {
        if (!store.containsKey(parentId)) {
            throw new TransactionNotFoundException(parentId);
        }
        if (parentId == id) {
            throw new InvalidTransactionException(
                    "Transaction " + id + " cannot be its own parent");
        }
        Set<Long> seen = new HashSet<>();
        Long ancestor = store.get(parentId).parentId();
        while (ancestor != null) {
            if (ancestor == id) {
                throw new InvalidTransactionException(
                        "Linking transaction " + id + " to parent " + parentId
                                + " would create a cycle");
            }
            if (!seen.add(ancestor)) {
                break; // defensive: the stored graph is already acyclic
            }
            Transaction next = store.get(ancestor);
            ancestor = (next == null) ? null : next.parentId();
        }
    }

    private void removeFromIndexes(Transaction previous) {
        Set<Long> typeSet = typeIndex.get(previous.type());
        if (typeSet != null) {
            typeSet.remove(previous.id());
        }
        if (previous.parentId() != null) {
            Set<Long> childSet = childrenIndex.get(previous.parentId());
            if (childSet != null) {
                childSet.remove(previous.id());
            }
        }
    }

    @Override
    public Optional<Transaction> findById(long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean existsById(long id) {
        return store.containsKey(id);
    }

    @Override
    public List<Long> findIdsByType(String type) {
        return typeIndex.getOrDefault(type, Set.of()).stream().sorted().toList();
    }

    @Override
    public Set<Long> childrenOf(long id) {
        return Set.copyOf(childrenIndex.getOrDefault(id, Set.of()));
    }

    @Override
    public synchronized void clear() {
        store.clear();
        typeIndex.clear();
        childrenIndex.clear();
    }
}
