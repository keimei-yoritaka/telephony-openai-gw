package com.example.telephonygw.sip;

import com.example.telephonygw.config.GatewayConfig.RegistrationConfig;
import com.example.telephonygw.config.GatewayConfig.SipConfig;
import com.example.telephonygw.session.CallSessionManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

final class Pjsua2SipEndpoint implements SipEndpointAdapter {
    private static final System.Logger LOG = System.getLogger(Pjsua2SipEndpoint.class.getName());

    private final SipConfig sipConfig;
    private final RegistrationConfig registrationConfig;
    private final CallSessionManager sessionManager;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean eventsRunning = new AtomicBoolean(false);

    private Object endpoint;
    private Object account;
    private Thread eventThread;
    private int transportId = -1;

    Pjsua2SipEndpoint(
            SipConfig sipConfig,
            RegistrationConfig registrationConfig,
            CallSessionManager sessionManager
    ) {
        this.sipConfig = sipConfig;
        this.registrationConfig = registrationConfig;
        this.sessionManager = sessionManager;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        try {
            endpoint = newInstance("org.pjsip.pjsua2.Endpoint");
            invoke(endpoint, "libCreate");
            invoke(endpoint, "libInit", newInstance("org.pjsip.pjsua2.EpConfig"));

            Object transportConfig = newInstance("org.pjsip.pjsua2.TransportConfig");
            invoke(transportConfig, "setPort", (long) sipConfig.port());
            invoke(transportConfig, "setBoundAddress", sipConfig.bindAddress());
            if (!sipConfig.publicContactAddress().isBlank()) {
                invoke(transportConfig, "setPublicAddress", sipConfig.publicContactAddress());
            }

            int udpTransport = staticInt("org.pjsip.pjsua2.pjsip_transport_type_e", "PJSIP_TRANSPORT_UDP");
            transportId = (Integer) invoke(endpoint, "transportCreate", udpTransport, transportConfig);
            invoke(endpoint, "libStart");
            preferPcmuCodec();
            startEventLoop();

            LOG.log(System.Logger.Level.INFO,
                    "Started PJSUA2 endpoint transportId={0} bind={1}:{2}/UDP IPv4",
                    transportId, sipConfig.bindAddress(), sipConfig.port());
        } catch (ReflectiveOperationException e) {
            started.set(false);
            throw new IllegalStateException("Failed to start PJSUA2 endpoint. Check PJSUA2 classpath and java.library.path.", e);
        } catch (RuntimeException e) {
            started.set(false);
            throw e;
        }
    }

    @Override
    public void register() {
        ensureStarted();
        try {
            Object accountConfig = buildAccountConfig();
            account = newInstance("org.pjsip.pjsua2.Account");
            invoke(account, "create", accountConfig, true);

            LOG.log(System.Logger.Level.INFO,
                    "Started SIP Registration for {0} via {1}:{2}",
                    registrationConfig.sipAddress(),
                    registrationConfig.registryServerAddress(),
                    registrationConfig.registryServerPort());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create PJSUA2 account for SIP Registration.", e);
        }
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }

        eventsRunning.set(false);
        if (eventThread != null) {
            eventThread.interrupt();
            try {
                eventThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        closeAccount();
        destroyEndpoint();
        sessionManager.closeAll("pjsua2_endpoint_stop");
        LOG.log(System.Logger.Level.INFO, "Stopped PJSUA2 endpoint");
    }

    private Object buildAccountConfig() throws ReflectiveOperationException {
        Object accountConfig = newInstance("org.pjsip.pjsua2.AccountConfig");
        invoke(accountConfig, "setIdUri", registrationConfig.sipAddress());

        Object regConfig = invoke(accountConfig, "getRegConfig");
        invoke(regConfig, "setRegistrarUri", registrarUri());
        invoke(regConfig, "setRegisterOnAdd", true);

        Object sipCfg = invoke(accountConfig, "getSipConfig");
        invoke(sipCfg, "setTransportId", transportId);
        Object authCreds = invoke(sipCfg, "getAuthCreds");
        Object credential = newAuthCredential();
        invoke(authCreds, "add", credential);

        return accountConfig;
    }

    private Object newAuthCredential() throws ReflectiveOperationException {
        Constructor<?> constructor = clazz("org.pjsip.pjsua2.AuthCredInfo")
                .getConstructor(String.class, String.class, String.class, int.class, String.class);
        return constructor.newInstance(
                "Digest",
                "*",
                registrationConfig.userName(),
                0,
                registrationConfig.password());
    }

    private String registrarUri() {
        return "sip:" + registrationConfig.registryServerAddress()
                + ":" + registrationConfig.registryServerPort()
                + ";transport=udp";
    }

    private void preferPcmuCodec() {
        try {
            invoke(endpoint, "codecSetPriority", "PCMU/8000", (short) 255);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to set PCMU codec priority. Continuing with PJSIP defaults: {0}",
                    e.getMessage());
        }
    }

    private void startEventLoop() {
        eventsRunning.set(true);
        eventThread = new Thread(() -> {
            try {
                invoke(endpoint, "libRegisterThread", "pjsua2-events");
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Failed to register PJSUA2 event thread: {0}", e.getMessage());
                eventsRunning.set(false);
                return;
            }
            while (eventsRunning.get()) {
                try {
                    invoke(endpoint, "libHandleEvents", 50L);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    if (eventsRunning.get()) {
                        LOG.log(System.Logger.Level.WARNING, "PJSUA2 event loop error: {0}", e.getMessage());
                    }
                }
            }
        }, "pjsua2-events");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    private void closeAccount() {
        if (account == null) {
            return;
        }
        try {
            invoke(account, "shutdown");
            invoke(account, "delete");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to close PJSUA2 account: {0}", e.getMessage());
        } finally {
            account = null;
        }
    }

    private void destroyEndpoint() {
        if (endpoint == null) {
            return;
        }
        try {
            invoke(endpoint, "libDestroy");
            invoke(endpoint, "delete");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to destroy PJSUA2 endpoint: {0}", e.getMessage());
        } finally {
            endpoint = null;
        }
    }

    private void ensureStarted() {
        if (!started.get()) {
            throw new IllegalStateException("PJSUA2 endpoint is not started");
        }
    }

    private static Class<?> clazz(String className) throws ClassNotFoundException {
        return Class.forName(className);
    }

    private static Object newInstance(String className) throws ReflectiveOperationException {
        return clazz(className).getConstructor().newInstance();
    }

    private static int staticInt(String className, String fieldName) throws ReflectiveOperationException {
        Field field = clazz(className).getField(fieldName);
        return field.getInt(null);
    }

    private static Object invoke(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName, args);
        return method.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String methodName, Object[] args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (!isCompatible(parameterTypes[i], args[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + methodName);
    }

    private static boolean isCompatible(Class<?> parameterType, Object arg) {
        if (arg == null) {
            return !parameterType.isPrimitive();
        }
        if (parameterType.isPrimitive()) {
            return primitiveWrapper(parameterType).isInstance(arg);
        }
        return parameterType.isInstance(arg);
    }

    private static Class<?> primitiveWrapper(Class<?> primitiveType) {
        if (primitiveType == int.class) {
            return Integer.class;
        }
        if (primitiveType == long.class) {
            return Long.class;
        }
        if (primitiveType == short.class) {
            return Short.class;
        }
        if (primitiveType == boolean.class) {
            return Boolean.class;
        }
        if (primitiveType == byte.class) {
            return Byte.class;
        }
        if (primitiveType == char.class) {
            return Character.class;
        }
        if (primitiveType == float.class) {
            return Float.class;
        }
        if (primitiveType == double.class) {
            return Double.class;
        }
        return Void.class;
    }
}
