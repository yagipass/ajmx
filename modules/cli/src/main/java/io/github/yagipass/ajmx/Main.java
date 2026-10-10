package io.github.yagipass.ajmx;

import io.github.yagipass.ajmx.cli.Cli;
import java.io.FileDescriptor;
import java.io.FileOutputStream;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int exitCode =
        Cli.run(
            args, System.in, new FileOutputStream(FileDescriptor.out), System.err, System.getenv());
    System.exit(exitCode);
  }
}
