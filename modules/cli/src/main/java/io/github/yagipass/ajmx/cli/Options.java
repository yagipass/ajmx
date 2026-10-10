package io.github.yagipass.ajmx.cli;

import com.google.errorprone.annotations.Var;
import io.github.yagipass.ajmx.core.Token;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

record Options(
    @Nullable Command command,
    List<String> args,
    @Nullable Long pid,
    @Nullable String url,
    long timeoutMs,
    int limit,
    long maxBytes,
    @Nullable String argsJson,
    @Nullable List<String> signature,
    boolean credentialsStdin,
    boolean debug,
    boolean help,
    boolean version) {

  static final String DEFAULT_TIMEOUT = "10s";
  static final int DEFAULT_LIMIT = 100;
  static final long DEFAULT_MAX_BYTES = 262_144;
  private static final long MIN_MAX_BYTES = 512;
  private static final Pattern DURATION = Pattern.compile("(\\d{1,9})(ms|s|m)?");

  static Options parse(String[] argv) {
    Parser parser = new Parser();
    @Var boolean optionsEnded = false;
    for (int i = 0; i < argv.length; i++) {
      String arg = argv[i];
      if (!optionsEnded && arg.equals("--")) {
        optionsEnded = true;
      } else if (!optionsEnded && arg.startsWith("--")) {
        int eq = arg.indexOf('=');
        String name = eq > 0 ? arg.substring(0, eq) : arg;
        @Var String value = eq > 0 ? arg.substring(eq + 1) : null;
        Option option = Option.find(name).orElseThrow(() -> invalid("Unknown option", name));
        if (!option.takesValue() && value != null) {
          throw invalid("Option takes no value", name);
        }
        if (option.takesValue() && value == null) {
          if (i + 1 >= argv.length) {
            throw invalid("Option requires a value", name);
          }
          value = argv[++i];
        }
        parser.set(option, value);
      } else {
        parser.positional(arg);
      }
    }
    return parser.build();
  }

  void validate() {
    if (pid != null && url != null) {
      throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "--pid and --url are mutually exclusive");
    }
    if (command == null) {
      throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Missing command")
          .with("allowed", Command.tokens());
    }
    if ((argsJson != null || signature != null) && command != Command.INVOKE) {
      throw new AjmxException(
          ErrorCode.INVALID_ARGUMENT, "--args and --signature apply only to invoke");
    }
    if (credentialsStdin && command == Command.BATCH) {
      throw new AjmxException(
          ErrorCode.INVALID_ARGUMENT,
          "batch reads requests from stdin; use JMX_USERNAME/JMX_PASSWORD for credentials");
    }
    if (credentialsStdin && url == null) {
      throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "--credentials-stdin requires --url");
    }
  }

  private static final class Parser {
    private final Set<Option> seen = EnumSet.noneOf(Option.class);
    private final List<String> args = new ArrayList<>();
    private @Nullable Command command;
    private @Nullable Long pid;
    private @Nullable String url;
    private long timeoutMs = duration(DEFAULT_TIMEOUT);
    private int limit = DEFAULT_LIMIT;
    private long maxBytes = DEFAULT_MAX_BYTES;
    private @Nullable String argsJson;
    private @Nullable List<String> signature;
    private boolean credentialsStdin;
    private boolean debug;
    private boolean help;
    private boolean version;

    @SuppressWarnings(
        "NullAway") // parse() passes a non-null value for every option that takesValue()
    private void set(Option option, @Nullable String value) {
      String name = option.flag();
      if (option.takesValue() && option != Option.SIGNATURE && !seen.add(option)) {
        throw invalid("Option given more than once", name);
      }
      switch (option) {
        case PID -> pid = positiveLong(name, value);
        case URL -> url = value;
        case CREDENTIALS_STDIN -> credentialsStdin = true;
        case TIMEOUT -> timeoutMs = duration(value);
        case LIMIT -> limit = (int) Math.min(Integer.MAX_VALUE, positiveLong(name, value));
        case MAX_BYTES -> {
          maxBytes = positiveLong(name, value);
          if (maxBytes < MIN_MAX_BYTES) {
            throw invalid("Value is too small", name).with("minimum", MIN_MAX_BYTES);
          }
        }
        case ARGS -> argsJson = value;
        case SIGNATURE -> {
          if (signature == null) {
            signature = new ArrayList<>();
          }
          for (String t : value.split(",", -1)) {
            if (!t.isBlank()) {
              signature.add(t.strip());
            }
          }
        }
        case DEBUG -> debug = true;
        case HELP -> help = true;
        case VERSION -> version = true;
      }
    }

    private void positional(String arg) {
      if (command == null) {
        command =
            Token.find(Command.values(), arg)
                .orElseThrow(
                    () ->
                        new AjmxException(ErrorCode.INVALID_ARGUMENT, "Unknown command")
                            .with("command", arg)
                            .with("allowed", Command.tokens()));
      } else {
        args.add(arg);
      }
    }

    private Options build() {
      return new Options(
          command,
          List.copyOf(args),
          pid,
          url,
          timeoutMs,
          limit,
          maxBytes,
          argsJson,
          signature == null ? null : List.copyOf(signature),
          credentialsStdin,
          debug,
          help,
          version);
    }
  }

  private static long positiveLong(String name, String value) {
    @Var long v;
    try {
      v = Long.parseLong(value);
    } catch (NumberFormatException e) {
      v = 0;
    }
    if (v <= 0) {
      throw invalid("Value must be a positive integer", name).with("value", value);
    }
    return v;
  }

  static long duration(String value) {
    Matcher m = DURATION.matcher(value);
    if (m.matches()) {
      long n = Long.parseLong(m.group(1));
      String unit = m.group(2) == null ? "s" : m.group(2);
      long ms =
          switch (unit) {
            case "ms" -> n;
            case "m" -> n * 60_000;
            default -> n * 1000;
          };
      if (ms > 0) {
        return ms;
      }
    }
    throw invalid("Invalid duration (examples: 500ms, 5s, 1m)", Option.TIMEOUT.flag())
        .with("value", value);
  }

  private static AjmxException invalid(String message, String option) {
    return new AjmxException(ErrorCode.INVALID_ARGUMENT, message).with("option", option);
  }
}
