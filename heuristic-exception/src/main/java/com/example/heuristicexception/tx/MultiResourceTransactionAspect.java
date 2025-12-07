package com.example.heuristicexception.tx;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class MultiResourceTransactionAspect {

    @Qualifier("compositeTxManager")
    private final PlatformTransactionManager compositeTxManager;

    @Around("@within(org.springframework.stereotype.Service) && execution(* com.example.heuristicexception..*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean firstCall = FirstServiceCallContext.isFirstCall();
        FirstServiceCallContext.markNotFirst();

        if (!firstCall) {
            return joinPoint.proceed();
        }

        TransactionStatus status = null;
        try {
            status = compositeTxManager.getTransaction(new DefaultTransactionDefinition());
            Object result = joinPoint.proceed();
            if (!status.isCompleted()) {
                if (!status.isRollbackOnly()) {
                    compositeTxManager.commit(status);
                } else {
                    compositeTxManager.rollback(status);
                }
            }
            return result;
        } catch (Throwable ex) {
            if (status != null && !status.isCompleted()) {
                compositeTxManager.rollback(status);
            }
            throw ex;
        } finally {
            FirstServiceCallContext.reset();
        }
    }
}
