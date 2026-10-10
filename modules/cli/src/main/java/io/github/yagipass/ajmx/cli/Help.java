package io.github.yagipass.ajmx.cli;

import java.util.LinkedHashMap;
import java.util.Map;

final class Help {
  private Help() {}

  static Map<String, Object> usage() {
    Map<String, Object> commands = new LinkedHashMap<>();
    for (Command c : Command.values()) {
      commands.put(c.usage(), c.description());
    }

    Map<String, Object> options = new LinkedHashMap<>();
    for (Option o : Option.values()) {
      options.put(o.usage(), o.description());
    }

    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put(
        "usage",
        "ajmx ["
            + Option.PID.usage()
            + " | "
            + Option.URL.usage()
            + "] [options] <command> [arguments]");
    usage.put("commands", commands);
    usage.put("options", options);
    return usage;
  }
}
