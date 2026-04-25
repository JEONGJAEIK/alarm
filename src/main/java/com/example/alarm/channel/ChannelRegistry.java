package com.example.alarm.channel;

import com.example.alarm.domain.NotificationChannelType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 채널 타입 → {@link NotificationChannel} 구현체 매핑을 보유한 레지스트리.
 *
 * <p>Spring 컨텍스트에 등록된 모든 {@link NotificationChannel} 빈을 생성자에서 수집한다.
 * 새 채널 추가는 새 {@code @Component}를 추가하는 것만으로 충분.
 */
@Component
public class ChannelRegistry {

    private final Map<NotificationChannelType, NotificationChannel> byType;

    public ChannelRegistry(List<NotificationChannel> channels) {
        this.byType = channels.stream()
                .collect(Collectors.toUnmodifiableMap(NotificationChannel::type, Function.identity()));
    }

    /**
     * 주어진 타입의 채널 구현체를 반환. 등록되지 않은 타입이면 예외.
     *
     * @throws IllegalStateException 해당 타입에 등록된 채널이 없을 때
     */
    public NotificationChannel resolve(NotificationChannelType type) {
        var ch = byType.get(type);
        if (ch == null) {
            throw new IllegalStateException("등록된 채널이 없습니다: " + type);
        }
        return ch;
    }
}
