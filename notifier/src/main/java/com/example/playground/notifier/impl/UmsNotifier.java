package com.example.playground.notifier.impl;

import com.example.playground.notifier.Notifier;
import com.example.playground.notifier.dto.UmsReqDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UmsNotifier implements Notifier<UmsReqDto> {

    @Override
    public void send(UmsReqDto request) {
        log.info("[UMS Notifier] Sending message: " + request.getMessage());
        // 문자 발송 로직
    }
}
