package com.example.telephonygw.logging;

public final class GatewayEventLogger {
    private static final String PREFIX = "GW_EVENT";

    private GatewayEventLogger() {
    }

    public static void info(System.Logger logger, String event, Object... fields) {
        logger.log(System.Logger.Level.INFO, format(event, fields));
    }

    public static void warning(System.Logger logger, String event, Object... fields) {
        logger.log(System.Logger.Level.WARNING, format(event, fields));
    }

    public static String format(String event, Object... fields) {
        StringBuilder message = new StringBuilder(PREFIX)
                .append(" event=")
                .append(sanitize(event));
        for (int i = 0; i + 1 < fields.length; i += 2) {
            message.append(' ')
                    .append(sanitize(String.valueOf(fields[i])))
                    .append('=')
                    .append(sanitize(String.valueOf(fields[i + 1])));
        }
        if (fields.length % 2 != 0) {
            message.append(" invalidFieldCount=").append(fields.length);
        }
        return message.toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        String compact = value
                .replace('\n', '_')
                .replace('\r', '_')
                .replace('\t', '_')
                .trim();
        if (compact.isBlank()) {
            return "\"\"";
        }
        return compact.replace(' ', '_');
    }
}
