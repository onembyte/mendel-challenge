package com.mendel.transactions.repository;

import com.mendel.transactions.exception.InvalidTransactionException;
import com.mendel.transactions.exception.TransactionNotFoundException;
import com.mendel.transactions.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTransactionRepositoryTest {

    private InMemoryTransactionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTransactionRepository();
    }

    @Test
    void storesAndRetrievesById() {
        Transaction tx = new Transaction(10L, 5000.0, "cars", null);
        repository.upsert(tx);

        assertThat(repository.findById(10L)).contains(tx);
        assertThat(repository.existsById(10L)).isTrue();
        assertThat(repository.existsById(999L)).isFalse();
        assertThat(repository.findById(999L)).isEmpty();
    }

    @Test
    void findIdsByTypeReturnsIdsSortedAscending() {
        repository.upsert(new Transaction(30L, 1.0, "shopping", null));
        repository.upsert(new Transaction(10L, 1.0, "shopping", null));
        repository.upsert(new Transaction(20L, 1.0, "shopping", null));

        assertThat(repository.findIdsByType("shopping")).containsExactly(10L, 20L, 30L);
    }

    @Test
    void findIdsByTypeReturnsEmptyForUnknownType() {
        assertThat(repository.findIdsByType("nope")).isEmpty();
    }

    @Test
    void childrenOfReturnsDirectChildrenOnly() {
        repository.upsert(new Transaction(1L, 1.0, "t", null));
        repository.upsert(new Transaction(2L, 1.0, "t", 1L));
        repository.upsert(new Transaction(3L, 1.0, "t", 1L));

        assertThat(repository.childrenOf(1L)).containsExactlyInAnyOrder(2L, 3L);
        assertThat(repository.childrenOf(2L)).isEmpty();
    }

    @Test
    void upsertReindexesTypeLeavingNoStaleEntries() {
        repository.upsert(new Transaction(5L, 1.0, "food", null));
        repository.upsert(new Transaction(5L, 1.0, "travel", null)); // update: type changed

        assertThat(repository.findIdsByType("food")).doesNotContain(5L);
        assertThat(repository.findIdsByType("travel")).containsExactly(5L);
    }

    @Test
    void upsertReindexesParentLeavingNoStaleEntries() {
        repository.upsert(new Transaction(1L, 1.0, "t", null));
        repository.upsert(new Transaction(2L, 1.0, "t", null));
        repository.upsert(new Transaction(3L, 1.0, "t", 1L)); // child of 1
        repository.upsert(new Transaction(3L, 1.0, "t", 2L)); // update: now child of 2

        assertThat(repository.childrenOf(1L)).doesNotContain(3L);
        assertThat(repository.childrenOf(2L)).containsExactly(3L);
    }

    @Test
    void upsertWithMissingParentThrowsNotFound() {
        assertThatThrownBy(() -> repository.upsert(new Transaction(1L, 1.0, "t", 999L)))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void upsertWithSelfParentThrowsInvalid() {
        repository.upsert(new Transaction(1L, 1.0, "t", null));

        assertThatThrownBy(() -> repository.upsert(new Transaction(1L, 1.0, "t", 1L)))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void upsertCreatingCycleThrowsInvalid() {
        repository.upsert(new Transaction(1L, 1.0, "t", null));
        repository.upsert(new Transaction(2L, 1.0, "t", 1L));
        repository.upsert(new Transaction(3L, 1.0, "t", 2L));

        // re-point 1 under 3 -> 1 -> 3 -> 2 -> 1 would be a cycle
        assertThatThrownBy(() -> repository.upsert(new Transaction(1L, 1.0, "t", 3L)))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void clearEmptiesAllState() {
        repository.upsert(new Transaction(1L, 1.0, "t", null));
        repository.upsert(new Transaction(2L, 1.0, "t", 1L));

        repository.clear();

        assertThat(repository.existsById(1L)).isFalse();
        assertThat(repository.findIdsByType("t")).isEmpty();
        assertThat(repository.childrenOf(1L)).isEmpty();
    }
}
