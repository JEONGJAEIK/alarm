package com.example.alarm.channel;

import lombok.Getter;

/**
 * 채널 발송 중 발생한 실패를 표현. {@link #isRetryable()}로 일시/영구 구분.
 */
@Getter
public class ChannelDeliveryException extends RuntimeException {
    private final boolean retryable;

    public ChannelDeliveryException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }
}
