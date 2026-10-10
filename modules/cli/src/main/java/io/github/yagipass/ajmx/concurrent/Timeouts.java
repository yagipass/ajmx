package io.github.yagipass.ajmx.concurrent;

import io.github.yagipass.ajmx.error.AjmxException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class Timeouts {
  @FunctionalInterface
  public interface Cleanup {
    void run() throws Exception;
  }

  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "ajmx-call");
            t.setDaemon(true);
            return t;
          });

  private Timeouts() {}

  public static <T extends @Nullable Object> T call(
      Callable<T> task,
      long timeoutMs,
      Supplier<AjmxException> onTimeout,
      Function<Throwable, AjmxException> translate) {
    return start(task, timeoutMs, onTimeout, translate).get();
  }

  public static <T extends @Nullable Object> Supplier<T> start(
      Callable<T> task,
      long timeoutMs,
      Supplier<AjmxException> onTimeout,
      Function<Throwable, AjmxException> translate) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    Future<T> future = EXECUTOR.submit(task);
    return () -> {
      try {
        return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (TimeoutException _) {
        future.cancel(true);
        throw translate.apply(onTimeout.get());
      } catch (ExecutionException e) {
        throw translate.apply(e.getCause() != null ? e.getCause() : e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        throw translate.apply(onTimeout.get());
      }
    };
  }

  public static void runQuietly(Cleanup task, long timeoutMs) {
    Future<?> future =
        EXECUTOR.submit(
            () -> {
              task.run();
              return null;
            });
    try {
      future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Error err) {
        throw err;
      }
    } catch (TimeoutException e) {
      future.cancel(true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
    }
  }
}
