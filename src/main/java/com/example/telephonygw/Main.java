package com.example.telephonygw;

import com.example.telephonygw.app.GatewayApp;
import com.example.telephonygw.config.GatewayConfig;
import com.example.telephonygw.config.GatewayConfigLoader;
import com.example.telephonygw.logging.LoggingConfigurator;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = resolveConfigPath(args);
        GatewayConfig config = GatewayConfigLoader.load(configPath);
        LoggingConfigurator.configure(config.logging().level());

        if (containsArg(args, "--check-config")) {
            System.getLogger(Main.class.getName()).log(System.Logger.Level.INFO,
                    "Configuration loaded successfully from {0}", configPath);
            return;
        }

        GatewayApp app = new GatewayApp(config);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "gateway-shutdown"));
        app.start();
        if (containsArg(args, "--startup-check")) {
            Thread.sleep(2000);
            app.stop();
            return;
        }
        app.awaitShutdown();
    }

    private static Path resolveConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }

        String envPath = System.getenv("GATEWAY_CONFIG");
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }

        return Path.of("config/gateway.example.yaml");
    }

    private static boolean containsArg(String[] args, String expected) {
        for (String arg : args) {
            if (expected.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
