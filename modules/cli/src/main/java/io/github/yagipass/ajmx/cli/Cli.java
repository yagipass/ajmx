package io.github.yagipass.ajmx.cli;

import com.google.errorprone.annotations.Var;
import io.github.yagipass.ajmx.connection.Connections;
import io.github.yagipass.ajmx.connection.Credentials;
import io.github.yagipass.ajmx.connection.JmxSession;
import io.github.yagipass.ajmx.connection.LocalJvms;
import io.github.yagipass.ajmx.connection.Target;
import io.github.yagipass.ajmx.core.Batch;
import io.github.yagipass.ajmx.core.MBeanClient;
import io.github.yagipass.ajmx.core.Outcome;
import io.github.yagipass.ajmx.core.Request;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.error.Execution;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class Cli {
  private sealed interface Result {
    record Success(Outcome outcome) implements Result {}

    record Failure(AjmxException error) implements Result {}
  }

  private Cli() {}

  public static int run(
      String[] args, InputStream in, OutputStream out, PrintStream err, Map<String, String> env) {
    long start = System.nanoTime();
    @Var Options options = null;
    @Var Result result;
    try {
      options = Options.parse(args);
      result = new Result.Success(execute(options, in, env));
    } catch (RuntimeException | Error e) {
      result = new Result.Failure(AjmxException.wrap(e));
    }
    if (options != null && options.debug()) {
      printStackTraces(err, result);
    }
    long durationMs = (System.nanoTime() - start) / 1_000_000;
    long maxBytes = options != null ? options.maxBytes() : Options.DEFAULT_MAX_BYTES;
    return emit(render(result, durationMs, maxBytes), out, err);
  }

  private static Outcome execute(Options options, InputStream in, Map<String, String> env) {
    if (options.help()) {
      return Outcome.of(Help.usage());
    }
    if (options.version()) {
      return Outcome.of(Version.json());
    }
    options.validate();
    Command command = Objects.requireNonNull(options.command());
    command.requireArgumentCount(options.args().size());
    List<String> args = options.args();
    return switch (command) {
      case PS ->
          Outcome.items(
              LocalJvms.list(options.timeoutMs()).stream().map(Cli::jvmJson).toList(),
              options.limit());
      case PING -> runRequest(options, command, in, env, new Request.Ping());
      case SEARCH ->
          runRequest(
              options, command, in, env, Request.search(args.isEmpty() ? null : args.getFirst()));
      case DESCRIBE -> runRequest(options, command, in, env, Request.describe(args.getFirst()));
      case READ ->
          runRequest(
              options,
              command,
              in,
              env,
              Request.read(args.getFirst(), args.subList(1, args.size())));
      case WRITE -> runRequest(options, command, in, env, writeRequest(args.get(0), args.get(1)));
      case INVOKE ->
          runRequest(
              options,
              command,
              in,
              env,
              Request.invoke(
                  args.get(0),
                  args.get(1),
                  parseInvokeArgs(options.argsJson()),
                  options.signature()));
      case BATCH -> {
        Target target = target(options, command, in, env);
        Batch batch = Batch.parse(Inputs.readStdin(in, options.timeoutMs()));
        yield withClient(options, target, client -> batch.run(client::run));
      }
      case HELP -> Outcome.of(Help.usage());
      case VERSION -> Outcome.of(Version.json());
    };
  }

  private static Outcome runRequest(
      Options options, Command command, InputStream in, Map<String, String> env, Request request) {
    return withClient(options, target(options, command, in, env), client -> client.run(request));
  }

  private static Request writeRequest(String mbean, String assignment) {
    int eq = assignment.indexOf('=');
    if (eq <= 0) {
      throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Expected <attribute>=<value>")
          .with("argument", assignment);
    }
    return Request.write(mbean, assignment.substring(0, eq), assignment.substring(eq + 1));
  }

  private static List<Object> parseInvokeArgs(@Nullable String text) {
    if (text == null) {
      return List.of();
    }
    if (Inputs.parseJson(text, "--args") instanceof List<?> list) {
      return new ArrayList<>(list);
    }
    throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "--args must be a JSON array");
  }

  private static Target target(
      Options options, Command command, InputStream in, Map<String, String> env) {
    if (options.pid() != null) {
      return new Target.Local(options.pid());
    }
    if (options.url() != null) {
      Credentials credentials =
          options.credentialsStdin()
              ? Inputs.readCredentialsFromStdin(in, options.timeoutMs())
              : Inputs.readCredentialsFromEnv(env);
      return new Target.Remote(options.url(), credentials);
    }
    throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "--pid or --url is required")
        .with("command", command.token());
  }

  private static Outcome withClient(
      Options options, Target target, Function<MBeanClient, Outcome> body) {
    try (JmxSession session = Connections.open(target, options.timeoutMs())) {
      return body.apply(new MBeanClient(session, options.limit(), options.maxBytes()));
    }
  }

  private static Map<String, Object> jvmJson(LocalJvms.Jvm jvm) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("pid", jvm.pid());
    item.put("mainClass", jvm.mainClass());
    item.put("displayName", jvm.displayName());
    return item;
  }

  private static void printStackTraces(PrintStream err, Result result) {
    switch (result) {
      case Result.Success(Outcome outcome) ->
          outcome
              .failuresByPath()
              .forEach(
                  (path, e) -> {
                    err.println("ajmx: .result" + path);
                    e.printStackTrace(err);
                  });
      case Result.Failure(AjmxException error) -> error.printStackTrace(err);
    }
  }

  private static OutputFitter.Output render(Result result, long durationMs, long maxBytes) {
    OutputFitter fitter = new OutputFitter(durationMs, maxBytes);
    try {
      return switch (result) {
        case Result.Success(Outcome outcome) -> fitter.success(outcome);
        case Result.Failure(AjmxException error) -> fitter.failure(error);
      };
    } catch (RuntimeException | Error e) {
      Execution execution =
          switch (result) {
            case Result.Success(Outcome outcome) -> outcome.execution();
            case Result.Failure(AjmxException error) -> error.execution();
          };
      return fitter.unfittedFailure(
          AjmxException.encodingFailed(e, maxBytes).withExecution(execution));
    }
  }

  private static int emit(OutputFitter.Output output, OutputStream out, PrintStream err) {
    try {
      out.write(output.bytes());
      out.flush();
    } catch (IOException e) {
      err.println("ajmx: failed to write output: " + e.getMessage());
      return ErrorCode.INTERNAL_ERROR.exitCode();
    }
    return output.exitCode();
  }
}
