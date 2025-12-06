package com.example.playground.notifier.dto;

import lombok.Getter;

@Getter
public class SMSReqDto extends UmsReqDto{

    public SMSReqDto(String memberId, String message) {
        super(memberId, message);
    }
}
