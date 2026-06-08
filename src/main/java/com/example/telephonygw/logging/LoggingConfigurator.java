package com.example.telephonygw.logging;

import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LoggingConfigurator {
    private LoggingConfigurator() {
    }

    public static void configure(String configuredLevel) {
        Level level = toJulLevel(configuredLevel);
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(level);
        }
    }

    private static Level toJulLevel(String configuredLevel) {
        return switch (configuredLevel.toUpperCase(Locale.ROOT)) {
            case "TRACE" -> Level.FINEST;
            case "DEBUG" -> Level.FINE;
            case "INFO" -> Level.INFO;
            case "WARN", "WARNING" -> Level.WARNING;
            case "ERROR" -> Level.SEVERE;
            default -> throw new IllegalArgumentException(
                    "logging.level must be TRACE, DEBUG, INFO, WARN, WARNING, or ERROR: " + configuredLevel);
        };
    }
}
