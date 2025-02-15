package com.example.playground.notifier;

public interface Notifier<T> {
    void send(T request);
}
