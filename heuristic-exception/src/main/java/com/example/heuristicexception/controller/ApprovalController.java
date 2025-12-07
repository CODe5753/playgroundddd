package com.example.heuristicexception.controller;

import com.example.heuristicexception.domain.ApprovalRequest;
import com.example.heuristicexception.service.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/approve")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping
    public ResponseEntity<String> approve(@RequestBody ApprovalRequest request) {
        approvalService.approveAndSendUms(request);
        return ResponseEntity.ok("OK");
    }
}
