package com.example.heuristicexception.tx;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.HeuristicCompletionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

/**
 * 여러 로컬 트랜잭션 매니저를 하나처럼 묶어 처리한다.
 * - 커밋 순서는 등록 순서대로 수행
 * - 커밋 중간 실패 시 이미 커밋된 자원이 존재하므로 HeuristicCompletionException을 던진다.
 */
@Slf4j
public class CompositeTransactionManager implements PlatformTransactionManager {

    private final List<PlatformTransactionManager> delegates;

    public CompositeTransactionManager(List<PlatformTransactionManager> delegates) {
        this.delegates = delegates;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        List<TransactionStatus> statuses = new ArrayList<>();
        for (PlatformTransactionManager delegate : delegates) {
            statuses.add(delegate.getTransaction(definition));
        }
        return new CompositeTransactionStatus(statuses);
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        CompositeTransactionStatus compositeStatus = (CompositeTransactionStatus) status;
        boolean anyCommitted = false;
        log.info("[CTM] commit start, totalTx={}", compositeStatus.statuses().size());
        try {
            for (int i = 0; i < compositeStatus.statuses().size(); i++) {
                TransactionStatus ts = compositeStatus.statuses().get(i);
                // 이미 롤백Only거나 완료되었으면 건너뜀
                if (!ts.isCompleted() && !ts.isRollbackOnly()) {
                    log.info("[CTM] commit idx={} start", i);
                    delegates.get(i).commit(ts);
                    anyCommitted = true;
                    log.info("[CTM] commit idx={} success", i);
                }
            }
            log.info("[CTM] commit end");
        } catch (Exception ex) {
            // 커밋 중간 실패: 일부는 커밋 완료, 일부는 아직 미커밋 → 혼합 상태
            rollbackRemaining(compositeStatus);
            log.error("[CTM] commit failed, anyCommitted={}, ex={}", anyCommitted, ex.getClass().getSimpleName(), ex);
            throw new HeuristicCompletionException(anyCommitted
                    ? HeuristicCompletionException.STATE_MIXED
                    : HeuristicCompletionException.STATE_ROLLED_BACK, ex);
        }
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        CompositeTransactionStatus compositeStatus = (CompositeTransactionStatus) status;
        rollbackRemaining(compositeStatus);
    }

    private void rollbackRemaining(CompositeTransactionStatus compositeStatus) {
        for (int i = compositeStatus.statuses().size() - 1; i >= 0; i--) {
            TransactionStatus ts = compositeStatus.statuses().get(i);
            if (!ts.isCompleted()) {
                try {
                    delegates.get(i).rollback(ts);
                } catch (Exception ignored) {
                    // 롤백 실패는 누적 기록만 남김
                }
            }
        }
    }

    private record CompositeTransactionStatus(List<TransactionStatus> statuses) implements TransactionStatus, org.springframework.transaction.SavepointManager {

        @Override
        public boolean isNewTransaction() {
            return statuses.stream().anyMatch(TransactionStatus::isNewTransaction);
        }

        @Override
        public boolean hasSavepoint() {
            return statuses.stream().anyMatch(TransactionStatus::hasSavepoint);
        }

        @Override
        public void setRollbackOnly() {
            statuses.forEach(TransactionStatus::setRollbackOnly);
        }

        @Override
        public boolean isRollbackOnly() {
            return statuses.stream().anyMatch(TransactionStatus::isRollbackOnly);
        }

        @Override
        public void flush() {
            statuses.forEach(TransactionStatus::flush);
        }

        @Override
        public boolean isCompleted() {
            return statuses.stream().allMatch(TransactionStatus::isCompleted);
        }

        @Override
        public Object createSavepoint() {
            return savepointManagers().isEmpty() ? null : savepointManagers().get(0).createSavepoint();
        }

        @Override
        public void rollbackToSavepoint(Object savepoint) {
            savepointManagers().forEach(spm -> spm.rollbackToSavepoint(savepoint));
        }

        @Override
        public void releaseSavepoint(Object savepoint) {
            savepointManagers().forEach(spm -> spm.releaseSavepoint(savepoint));
        }

        private List<org.springframework.transaction.SavepointManager> savepointManagers() {
            return statuses.stream()
                    .filter(sp -> sp instanceof org.springframework.transaction.SavepointManager)
                    .map(sp -> (org.springframework.transaction.SavepointManager) sp)
                    .toList();
        }
    }
}
