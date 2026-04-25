package com.example.alarm.template;

import com.example.alarm.domain.NotificationChannelType;
import com.example.alarm.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 타입/채널 템플릿을 referenceData 값으로 치환해 title/body를 생성.
 *
 * <p>placeholder 형식은 {@code {{key}}}이며 공백을 허용한다 ({@code {{ key }}}).
 * 등록된 템플릿이 없으면 타입 이름을 title, referenceData 문자열을 body로 사용.
 */
@Component
@RequiredArgsConstructor
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");

    private final NotificationTemplateRepository repo;

    /**
     * 렌더링 결과: 발송 시 사용할 title과 body.
     */
    public record Rendered(String title, String body) {}

    /**
     * 템플릿이 있으면 치환, 없으면 fallback으로 렌더링.
     *
     * @param type 알림 타입
     * @param channel 발송 채널
     * @param data placeholder 치환에 사용할 키-값 맵 (null 허용)
     */
    public Rendered render(NotificationType type, NotificationChannelType channel,
                           Map<String, Object> data) {
        return repo.findByTypeAndChannel(type, channel)
                .map(t -> new Rendered(apply(t.getTitleTemplate(), data),
                                       apply(t.getBodyTemplate(), data)))
                .orElseGet(() -> new Rendered(type.name(), data == null ? "" : data.toString()));
    }

    private String apply(String template, Map<String, Object> data) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = data == null ? null : data.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
