package com.example.playground.notifier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class NotifierCoordinator {
    private final Map<Class<?>, Notifier<?>> notifierMap;

    public NotifierCoordinator(List<Notifier<?>> notifiers) {
        this.notifierMap = notifiers.stream()
                .collect(Collectors.toMap(
                        this::getGenericType, // 제네릭 타입 자동 추출
                        Function.identity()
                ));
    }

    @SuppressWarnings("unchecked")
    public <T> void send(T request) {
        for (Map.Entry<Class<?>, Notifier<?>> entry : notifierMap.entrySet()) {
            if (entry.getKey().isAssignableFrom(request.getClass())) {
                Notifier<T> selectedNotifier = (Notifier<T>) entry.getValue();
                log.info("📌 [NotifierCoordinator] Request class: {}", request.getClass().getSimpleName());
                log.info("🔍 [NotifierCoordinator] Matched Notifier Type: {}", entry.getKey().getSimpleName());
                log.info("🚀 [NotifierCoordinator] Selected Notifier: {}", selectedNotifier.getClass().getSimpleName());
                selectedNotifier.send(request);
                return;
            }
        }
        throw new IllegalArgumentException("No suitable notifier found for " + request.getClass().getSimpleName());
    }

    private Class<?> getGenericType(Notifier<?> notifier) {
        for (Type type : notifier.getClass().getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterizedType) {
                Type rawType = parameterizedType.getRawType();
                if (rawType instanceof Class<?> clazz && Notifier.class.isAssignableFrom(clazz)) {
                    return (Class<?>) parameterizedType.getActualTypeArguments()[0];
                }
            }
        }
        throw new IllegalStateException("Unable to determine generic type for Notifier: " + notifier.getClass().getName());
    }
}
