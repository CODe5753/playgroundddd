package com.example.heuristicexception.tx;

class FirstServiceCallContext {
    private static final ThreadLocal<Boolean> FIRST = ThreadLocal.withInitial(() -> true);

    static boolean isFirstCall() {
        return FIRST.get();
    }

    static void markNotFirst() {
        FIRST.set(false);
    }

    static void reset() {
        FIRST.set(true);
    }
}
