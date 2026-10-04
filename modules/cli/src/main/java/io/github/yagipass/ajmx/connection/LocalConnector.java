package io.github.yagipass.ajmx.connection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.Var;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.AttachOperationFailedException;
import com.sun.tools.attach.VirtualMachine;

import io.github.yagipass.ajmx.concurrent.Timeouts;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

final class LocalConnector {
    private static final long DETACH_TIMEOUT_MS = 1000;
    private static final long ATTACH_POLL_STEP_MS = 100;
    private static final long ATTACH_MARGIN_MS = 100;
    private static final long HANDSHAKE_END_TIMEOUT_MS = 1000;
    private static final long HANDSHAKE_INTERRUPT_STEP_MS = 10;
    private static final String DELETED = " (deleted)";

    private LocalConnector() {
    }

    static JMXConnector connect(long pid, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Map<String, Object> context = Map.of("pid", pid);
        AtomicReference<Handshake> handshake = new AtomicReference<>();
        return Timeouts.call(() -> attachAndConnect(pid, timeoutMs, deadline, handshake), timeoutMs,
                () -> timedOutDuring(handshake.get(), timeoutMs), e -> JmxErrors.translate(e, context));
    }

    record Handshake(Thread thread, boolean hasPerfData, CompletableFuture<AjmxException> outcome) {
        void end() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_END_TIMEOUT_MS);
            while (!outcome.isDone() && deadline - System.nanoTime() > 0) {
                thread.interrupt();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_INTERRUPT_STEP_MS));
            }
        }

        boolean answered() {
            return outcome.isDone() && outcome.join() == null;
        }
    }

    static AjmxException timedOutDuring(Handshake handshake, long timeoutMs) {
        if (handshake == null) {
            return timedOut(timeoutMs, null);
        }
        if (handshake.outcome().isDone()) {
            AjmxException failure = handshake.outcome().join();
            return failure != null ? failure : timedOut(timeoutMs, null);
        }
        handshake.end();
        return handshake.answered() ? timedOut(timeoutMs, null) : notResponding(timeoutMs, null, handshake.hasPerfData());
    }

    @SuppressWarnings("BanJNDI")
    private static JMXConnector attachAndConnect(long pid, long timeoutMs, long deadline,
            AtomicReference<Handshake> handshake) throws IOException {
        ProcessHandle process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow(
                () -> new AjmxException(ErrorCode.PROCESS_NOT_FOUND, "Process was not found"));
        ProcessHandle.Info info = process.info();
        requireSameUser(info);
        Optional<PerfData> perf = PerfDataFiles.load(process);
        if (perf.isEmpty() && !info.command().map(LocalConnector::isJavaLauncher).orElse(false)) {
            throw new AjmxException(ErrorCode.ATTACH_NOT_SUPPORTED,
                    "The process is not recognizable as a HotSpot JVM (no perf data and not the java launcher)");
        }
        @Var String address = perf.map(PerfData::connectorAddress).orElse(null);
        if (address == null) {
            if (perf.isPresent() && !perf.get().attachable()) {
                throw new AjmxException(ErrorCode.ATTACH_NOT_SUPPORTED, "The JVM disables the attach mechanism");
            }
            address = startLocalAgent(pid, timeoutMs, deadline, perf.isPresent(), handshake);
        }
        return JMXConnectorFactory.connect(new JMXServiceURL(address));
    }

    private static void requireSameUser(ProcessHandle.Info info) {
        String owner = info.user().orElse(null);
        String self = System.getProperty("user.name");
        if (owner != null && self != null && !owner.equals(self) && !self.equals("root")) {
            throw new AjmxException(ErrorCode.ATTACH_PERMISSION_DENIED, "The process belongs to another user")
                    .with("processUser", owner);
        }
    }

    static boolean isJavaLauncher(String command) {
        String path = command.endsWith(DELETED) ? command.substring(0, command.length() - DELETED.length()) : command;
        Path name = Path.of(path).getFileName();
        return name != null && name.toString().equals("java");
    }

    private static String startLocalAgent(long pid, long timeoutMs, long deadline, boolean hasPerfData,
            AtomicReference<Handshake> handshake) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        System.setProperty("sun.tools.attach.attachTimeout", Long.toString(attachTimeout(remainingMs - ATTACH_MARGIN_MS)));
        Handshake attempt = new Handshake(Thread.currentThread(), hasPerfData, new CompletableFuture<>());
        handshake.set(attempt);
        VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
        } catch (AttachNotSupportedException | IOException e) {
            AjmxException classified = classifyAttachFailure(timeoutMs, e, hasPerfData);
            attempt.outcome().complete(classified);
            throw classified;
        } finally {
            attempt.outcome().complete(null);
        }
        try {
            return vm.startLocalManagementAgent();
        } catch (IOException e) {
            throw agentUnavailable(e);
        } finally {
            Timeouts.runQuietly(vm::detach, DETACH_TIMEOUT_MS);
        }
    }

    static long attachTimeout(long budgetMs) {
        @Var long waited = ATTACH_POLL_STEP_MS;
        for (long delay = 2 * ATTACH_POLL_STEP_MS; waited + delay <= budgetMs; delay += ATTACH_POLL_STEP_MS) {
            waited += delay;
        }
        return waited - 1;
    }

    private static AjmxException timedOut(long timeoutMs, @Nullable Exception cause) {
        return new AjmxException(ErrorCode.CONNECTION_TIMEOUT, "Timed out connecting to the local JVM", cause)
                .with("timeoutMs", timeoutMs);
    }

    static AjmxException agentUnavailable(IOException e) {
        AjmxException unavailable = new AjmxException(ErrorCode.LOCAL_JMX_UNAVAILABLE,
                "Unable to start the local JMX agent in the JVM", e).with("reason", e.getMessage());
        return e instanceof AttachOperationFailedException ? unavailable.disableRetry() : unavailable;
    }

    private static AjmxException notResponding(long timeoutMs, @Nullable Exception cause, boolean hasPerfData) {
        return hasPerfData ? timedOut(timeoutMs, cause)
                : new AjmxException(ErrorCode.ATTACH_NOT_SUPPORTED,
                        "The process did not respond to attach (it may not be a HotSpot JVM, or may disable attach)", cause)
                        .with("timeoutMs", timeoutMs);
    }

    static AjmxException classifyAttachFailure(long timeoutMs, Exception e, boolean hasPerfData) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (m.contains("doesn't respond within")) {
            return notResponding(timeoutMs, e, hasPerfData);
        }
        ErrorCode code;
        String message;
        if (m.contains("not secure") || m.contains("permission denied") || m.contains("operation not permitted")) {
            code = ErrorCode.ATTACH_PERMISSION_DENIED;
            message = "Not permitted to attach to the process";
        } else if (m.contains("non existent") || m.contains("no such process")) {
            code = ErrorCode.PROCESS_NOT_FOUND;
            message = "Process was not found";
        } else {
            code = ErrorCode.ATTACH_NOT_SUPPORTED;
            message = "The process does not accept attach (not a HotSpot JVM, or attach is unavailable)";
        }
        return new AjmxException(code, message, e);
    }
}
