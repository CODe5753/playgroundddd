package com.example.playground.notifier.service;

import com.example.playground.notifier.Notifier;
import com.example.playground.notifier.NotifierCoordinator;
import com.example.playground.notifier.dto.UmsReqDto;
import com.example.playground.notifier.impl.UmsNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final NotifierCoordinator notifierCoordinator;

    public void payment() {
        log.info("payment method");

        // Do something...

        notifierCoordinator.send(new UmsReqDto("memberId", "message"));
    }
}
