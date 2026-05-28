package com.example.telephonygw.util;

public final class ShutdownHooks {
    private ShutdownHooks() {
    }

    public static void add(String name, Runnable action) {
        Runtime.getRuntime().addShutdownHook(new Thread(action, name));
    }
}

