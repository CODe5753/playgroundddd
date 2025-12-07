package com.example.heuristicexception.domain;

import java.math.BigDecimal;

public record ApprovalRequest(
        String approvalId,
        BigDecimal amount,
        String phoneNumber,
        String message
) {
}
