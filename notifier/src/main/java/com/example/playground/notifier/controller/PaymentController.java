package com.example.playground.notifier.controller;

import com.example.playground.notifier.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/pay")
    public ResponseEntity<Void> testPayment() {
        paymentService.payment();
        return ResponseEntity.ok(null);
    }
}
