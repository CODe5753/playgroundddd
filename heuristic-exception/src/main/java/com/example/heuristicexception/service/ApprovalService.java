package com.example.heuristicexception.service;

import com.example.heuristicexception.domain.ApprovalRequest;
import com.example.heuristicexception.mapper.db1.ApprovalHistoryMapper;
import com.example.heuristicexception.mapper.db2.UmsSendHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalHistoryMapper approvalHistoryMapper;
    private final UmsSendHistoryMapper umsSendHistoryMapper;

    public void approveAndSendUms(ApprovalRequest request) {
        log.info("[APPROVE] start approvalId={}", request.approvalId());
        log.info("승인 내역 DB1 적재");
        approvalHistoryMapper.insertApproval(request);

        log.info("UMS 내역 DB2 적재 (부하/타임아웃 시 커밋 실패 가능)");
        umsSendHistoryMapper.insertUmsHistory(request);
        log.info("[APPROVE] end approvalId={}", request.approvalId());
    }
}
