package com.example.telephonygw.sip;

import com.example.telephonygw.config.GatewayConfig.LoggingConfig;
import com.example.telephonygw.config.GatewayConfig.RegistrationConfig;
import com.example.telephonygw.config.GatewayConfig.SessionSlotConfig;
import com.example.telephonygw.config.GatewayConfig.SipConfig;
import com.example.telephonygw.media.AudioBridge;
import com.example.telephonygw.session.CallSessionManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class Pjsua2SipEndpoint implements SipEndpointAdapter, RegistrationAddressObserver {
    private static final System.Logger LOG = System.getLogger(Pjsua2SipEndpoint.class.getName());

    private final List<SlotRuntime> slots;
    private final LoggingConfig loggingConfig;
    private final CallSessionManager sessionManager;
    private final AudioBridge audioBridge;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean eventsRunning = new AtomicBoolean(false);

    private Object endpoint;
    private Thread eventThread;

    Pjsua2SipEndpoint(
            List<SessionSlotConfig> sessionSlots,
            LoggingConfig loggingConfig,
            CallSessionManager sessionManager,
            AudioBridge audioBridge
    ) {
        this.slots = sessionSlots.stream().map(SlotRuntime::new).toList();
        this.loggingConfig = loggingConfig;
        this.sessionManager = sessionManager;
        this.audioBridge = audioBridge;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        try {
            endpoint = newInstance("org.pjsip.pjsua2.Endpoint");
            invoke(endpoint, "libCreate");
            Object epConfig = newInstance("org.pjsip.pjsua2.EpConfig");
            configureNativeLogging(epConfig);
            invoke(endpoint, "libInit", epConfig);

            for (SlotRuntime slot : slots) {
                createTransport(slot);
            }

            invoke(endpoint, "libStart");
            logAvailableCodecs();
            configureCodecPolicy();
            startEventLoop();

            LOG.log(System.Logger.Level.INFO,
                    "Started PJSUA2 endpoint with {0} session slot(s)",
                    slots.size());
        } catch (ReflectiveOperationException e) {
            started.set(false);
            throw new IllegalStateException(
                    "Failed to start PJSUA2 endpoint. Check PJSUA2 classpath and java.library.path.", e);
        } catch (RuntimeException e) {
            started.set(false);
            throw e;
        }
    }

    @Override
    public void register() {
        ensureStarted();
        for (SlotRuntime slot : slots) {
            try {
                Object accountConfig = buildAccountConfig(slot);
                Object account = newAccount(slot);
                invoke(account, "create", accountConfig, true);
                slot.account = account;

                LOG.log(System.Logger.Level.INFO,
                        "Started SIP Registration for {0} via {1}:{2}: slotId={3}",
                        slot.registrationConfig().sipAddress(),
                        slot.registrationConfig().registryServerAddress(),
                        slot.registrationConfig().registryServerPort(),
                        slot.slotId());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Failed to create PJSUA2 account for SIP Registration: slotId=" + slot.slotId(), e);
            }
        }
    }

    @Override
    public void onRegistrationReflexiveAddressDetected(String slotId, String publicAddress, int publicPort) {
        SlotRuntime slot = slot(slotId);
        if (slot == null) {
            return;
        }
        if (!configuredPublicAddress(slot).isBlank()) {
            LOG.log(System.Logger.Level.INFO,
                    "Detected SIP Registration reflexive address {0}:{1}, keeping configured public address {2}: slotId={3}",
                    publicAddress, publicPort, configuredPublicAddress(slot), slotId);
            return;
        }
        String previous = slot.detectedPublicAddress.getAndSet(publicAddress);
        if (Objects.equals(previous, publicAddress)) {
            return;
        }
        LOG.log(System.Logger.Level.INFO,
                "Detected SIP Registration reflexive address from Via rport/received: slotId={0}, publicAddress={1}, publicPort={2}",
                slotId, publicAddress, publicPort);
        slot.pendingPublicAddressUpdate.set(publicAddress);
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

        registerCurrentThread("pjsua2-stop");
        closeAccounts();
        destroyEndpoint();
        LOG.log(System.Logger.Level.INFO, "Stopped PJSUA2 endpoint");
    }

    private void createTransport(SlotRuntime slot) throws ReflectiveOperationException {
        Object transportConfig = newInstance("org.pjsip.pjsua2.TransportConfig");
        invoke(transportConfig, "setPort", (long) slot.sipConfig().port());
        invoke(transportConfig, "setBoundAddress", slot.sipConfig().bindAddress());
        if (!configuredPublicAddress(slot).isBlank()) {
            invoke(transportConfig, "setPublicAddress", configuredPublicAddress(slot));
        }

        int udpTransport = staticInt("org.pjsip.pjsua2.pjsip_transport_type_e", "PJSIP_TRANSPORT_UDP");
        slot.transportId = (Integer) invoke(endpoint, "transportCreate", udpTransport, transportConfig);
        LOG.log(System.Logger.Level.INFO,
                "Created PJSUA2 UDP transport: slotId={0}, transportId={1}, bind={2}:{3}",
                slot.slotId(), slot.transportId, slot.sipConfig().bindAddress(), slot.sipConfig().port());
    }

    private Object buildAccountConfig(SlotRuntime slot) throws ReflectiveOperationException {
        Object accountConfig = newInstance("org.pjsip.pjsua2.AccountConfig");
        invoke(accountConfig, "setIdUri", slot.registrationConfig().sipAddress());

        Object regConfig = invoke(accountConfig, "getRegConfig");
        invoke(regConfig, "setRegistrarUri", registrarUri(slot.registrationConfig()));
        invoke(regConfig, "setRegisterOnAdd", true);

        Object sipCfg = invoke(accountConfig, "getSipConfig");
        invoke(sipCfg, "setTransportId", slot.transportId);
        Object authCreds = invoke(sipCfg, "getAuthCreds");
        Object credential = newAuthCredential(slot.registrationConfig());
        invoke(authCreds, "add", credential);

        configureAccountMedia(slot, accountConfig);

        return accountConfig;
    }

    private void configureAccountMedia(SlotRuntime slot, Object accountConfig) throws ReflectiveOperationException {
        SipConfig sipConfig = slot.sipConfig();
        Object mediaConfig = invoke(accountConfig, "getMediaConfig");
        Object mediaTransportConfig = invoke(mediaConfig, "getTransportConfig");
        invoke(mediaTransportConfig, "setBoundAddress", sipConfig.bindAddress());
        invoke(mediaTransportConfig, "setPort", (long) sipConfig.rtpPortStart());
        invoke(mediaTransportConfig, "setPortRange", (long) rtpPortRange(sipConfig));
        invoke(mediaTransportConfig, "setRandomizePort", true);
        String publicAddress = effectivePublicAddress(slot);
        if (!publicAddress.isBlank()) {
            invoke(mediaTransportConfig, "setPublicAddress", publicAddress);
        }
        invoke(mediaConfig, "setTransportConfig", mediaTransportConfig);
        invoke(mediaConfig, "setStreamKaEnabled", true);

        LOG.log(System.Logger.Level.INFO,
                "Configured PJSUA2 media transport NAT advertisement: slotId={0}, publicAddress={1}, bindAddress={2}, rtpPortRange={3}-{4}, randomizePort=true, streamKeepAlive=true",
                slot.slotId(),
                publicAddress.isBlank() ? "(auto)" : publicAddress,
                sipConfig.bindAddress(),
                sipConfig.rtpPortStart(),
                sipConfig.rtpPortEnd());
    }

    private int rtpPortRange(SipConfig sipConfig) {
        return sipConfig.rtpPortEnd() - sipConfig.rtpPortStart();
    }

    private void applyDetectedPublicAddress(SlotRuntime slot, String publicAddress) {
        Object currentAccount = slot.account;
        if (currentAccount == null) {
            return;
        }
        registerCurrentThread("pjsua2-registration-address-update");
        try {
            Object accountConfig = buildAccountConfig(slot);
            invoke(currentAccount, "modify", accountConfig);
            LOG.log(System.Logger.Level.INFO,
                    "Updated PJSUA2 account media public address from SIP Registration reflexive address: slotId={0}, publicAddress={1}",
                    slot.slotId(), publicAddress);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to update PJSUA2 account media public address from SIP Registration reflexive address {0}: slotId={1}, error={2}",
                    publicAddress, slot.slotId(), e.getMessage());
        }
    }

    private void configureNativeLogging(Object epConfig) throws ReflectiveOperationException {
        Object logConfig = invoke(epConfig, "getLogConfig");
        String level = loggingConfig.level().toUpperCase(Locale.ROOT);
        long nativeLevel = switch (level) {
            case "TRACE" -> 6L;
            case "DEBUG" -> 5L;
            case "INFO" -> 3L;
            case "WARN", "WARNING" -> 2L;
            case "ERROR" -> 1L;
            default -> 3L;
        };
        long consoleLevel = switch (level) {
            case "TRACE" -> 5L;
            case "DEBUG" -> 4L;
            case "INFO" -> 3L;
            case "WARN", "WARNING" -> 2L;
            case "ERROR" -> 1L;
            default -> 3L;
        };
        long messageLogging = ("TRACE".equals(level) || "DEBUG".equals(level)) ? 1L : 0L;
        invoke(logConfig, "setLevel", nativeLevel);
        invoke(logConfig, "setConsoleLevel", consoleLevel);
        invoke(logConfig, "setMsgLogging", messageLogging);
    }

    private Object newAccount(SlotRuntime slot) throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = clazz("com.example.telephonygw.sip.Pjsua2Account")
                    .getConstructor(String.class, CallSessionManager.class, AudioBridge.class,
                            RegistrationAddressObserver.class);
            return constructor.newInstance(slot.slotId(), sessionManager, audioBridge, this);
        } catch (ClassNotFoundException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "PJSUA2 account callback class is not available. Incoming INVITE may be rejected by PJSIP.");
            return newInstance("org.pjsip.pjsua2.Account");
        }
    }

    private Object newAuthCredential(RegistrationConfig registrationConfig) throws ReflectiveOperationException {
        Constructor<?> constructor = clazz("org.pjsip.pjsua2.AuthCredInfo")
                .getConstructor(String.class, String.class, String.class, int.class, String.class);
        return constructor.newInstance(
                "Digest",
                "*",
                registrationConfig.userName(),
                0,
                registrationConfig.password());
    }

    private String registrarUri(RegistrationConfig registrationConfig) {
        return "sip:" + registrationConfig.registryServerAddress()
                + ":" + registrationConfig.registryServerPort()
                + ";transport=udp";
    }

    private String configuredPublicAddress(SlotRuntime slot) {
        return slot.sipConfig().publicContactAddress();
    }

    private String effectivePublicAddress(SlotRuntime slot) {
        String configured = configuredPublicAddress(slot);
        if (!configured.isBlank()) {
            return configured;
        }
        String detected = slot.detectedPublicAddress.get();
        return detected == null ? "" : detected;
    }

    private void configureCodecPolicy() {
        try {
            Object codecs = invoke(endpoint, "codecEnum2");
            Map<String, Short> enabledCodecs = codecPriorityByPrefix();
            int disabled = 0;
            int codecCount = (Integer) invoke(codecs, "size");
            for (int i = 0; i < codecCount; i++) {
                Object codec = invoke(codecs, "get", i);
                String codecId = (String) invoke(codec, "getCodecId");
                Short priority = priorityForCodec(codecId, enabledCodecs);
                if (priority == null) {
                    invoke(endpoint, "codecSetPriority", codecId, (short) 0);
                    disabled++;
                } else {
                    invoke(endpoint, "codecSetPriority", codecId, priority);
                }
            }
            for (Map.Entry<String, Short> entry : enabledCodecs.entrySet()) {
                invoke(endpoint, "codecSetPriority", entry.getKey(), entry.getValue());
            }
            LOG.log(System.Logger.Level.INFO,
                    "Configured PJSUA2 codec policy: enabledCodecs={0}, disabledCodecs={1}",
                    enabledCodecs, disabled);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to set configured codec priority. Continuing with PJSIP defaults: {0}",
                    e.getMessage());
        }
    }

    private Map<String, Short> codecPriorityByPrefix() {
        Map<String, Short> priorities = new LinkedHashMap<>();
        short priority = 255;
        for (SlotRuntime slot : slots) {
            putCodecPriority(priorities, slot.sipConfig().preferredCodec(), priority);
            for (String codec : slot.sipConfig().codecs()) {
                if (!priorities.containsKey(codecPrefix(codec))) {
                    priority = (short) Math.max(1, priority - 16);
                    putCodecPriority(priorities, codec, priority);
                }
            }
        }
        return priorities;
    }

    private static void putCodecPriority(Map<String, Short> priorities, String codec, short priority) {
        priorities.putIfAbsent(codecPrefix(codec), priority);
    }

    private static String codecPrefix(String codec) {
        return switch (codec.toUpperCase(Locale.ROOT)) {
            case "G722" -> "G722/16000";
            case "PCMU" -> "PCMU/8000";
            default -> throw new IllegalArgumentException("Unsupported codec: " + codec);
        };
    }

    private static Short priorityForCodec(String codecId, Map<String, Short> priorities) {
        for (Map.Entry<String, Short> entry : priorities.entrySet()) {
            if (codecId.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void logAvailableCodecs() {
        try {
            Object codecs = invoke(endpoint, "codecEnum2");
            int codecCount = (Integer) invoke(codecs, "size");
            List<String> codecIds = new ArrayList<>();
            boolean g722Available = false;
            boolean pcmuAvailable = false;
            for (int i = 0; i < codecCount; i++) {
                Object codec = invoke(codecs, "get", i);
                String codecId = (String) invoke(codec, "getCodecId");
                codecIds.add(codecId);
                if (codecId.startsWith("G722/")) {
                    g722Available = true;
                }
                if (codecId.startsWith("PCMU/8000")) {
                    pcmuAvailable = true;
                }
            }
            LOG.log(System.Logger.Level.INFO,
                    "Available PJSUA2 audio codecs: pcmuAvailable={0}, g722Available={1}, codecCount={2}, codecIds={3}",
                    pcmuAvailable, g722Available, codecCount, String.join(",", codecIds));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to enumerate PJSUA2 codecs: {0}",
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
                    applyPendingPublicAddressUpdates();
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

    private void applyPendingPublicAddressUpdates() {
        for (SlotRuntime slot : slots) {
            String publicAddress = slot.pendingPublicAddressUpdate.getAndSet(null);
            if (publicAddress != null) {
                applyDetectedPublicAddress(slot, publicAddress);
            }
        }
    }

    private void registerCurrentThread(String name) {
        if (endpoint == null) {
            return;
        }
        try {
            invoke(endpoint, "libRegisterThread", name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to register current PJSUA2 thread {0}: {1}",
                    name, e.getMessage());
        }
    }

    private void closeAccounts() {
        for (SlotRuntime slot : slots) {
            if (slot.account == null) {
                continue;
            }
            try {
                invoke(slot.account, "shutdown");
                invoke(slot.account, "delete");
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Failed to close PJSUA2 account: slotId={0}, error={1}",
                        slot.slotId(), e.getMessage());
            } finally {
                slot.account = null;
            }
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

    private SlotRuntime slot(String slotId) {
        for (SlotRuntime slot : slots) {
            if (slot.slotId().equals(slotId)) {
                return slot;
            }
        }
        return null;
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

    private static final class SlotRuntime {
        private final SessionSlotConfig sessionSlot;
        private final AtomicReference<String> detectedPublicAddress = new AtomicReference<>();
        private final AtomicReference<String> pendingPublicAddressUpdate = new AtomicReference<>();
        private int transportId = -1;
        private Object account;

        private SlotRuntime(SessionSlotConfig sessionSlot) {
            this.sessionSlot = sessionSlot;
        }

        private String slotId() {
            return sessionSlot.slotId();
        }

        private SipConfig sipConfig() {
            return sessionSlot.sip();
        }

        private RegistrationConfig registrationConfig() {
            return sessionSlot.registration();
        }
    }
}
