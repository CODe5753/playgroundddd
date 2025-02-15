package com.example.playground.notifier.impl;

import com.example.playground.notifier.Notifier;
import com.example.playground.notifier.dto.AppNotiReqDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AppNotifier implements Notifier<AppNotiReqDto> {

    @Override
    public void send(AppNotiReqDto request) {
        log.info("[App Notifier] Sending message: " + request.getMessage());
        // 앱 알림 발송 로직
    }
}
