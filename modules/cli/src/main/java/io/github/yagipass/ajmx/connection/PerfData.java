package io.github.yagipass.ajmx.connection;

import com.google.errorprone.annotations.Var;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

final class PerfData {
  private static final int MAGIC = 0xcafec0c0;
  private static final int PROLOGUE_SIZE = 32;
  private static final int ENTRY_HEADER_SIZE = 20;

  private final Map<String, Object> entries;

  private PerfData(Map<String, Object> entries) {
    this.entries = entries;
  }

  @Nullable String javaCommand() {
    return entries.get("sun.rt.javaCommand") instanceof String s ? s : null;
  }

  @Nullable String connectorAddress() {
    return entries.get("sun.management.JMXConnectorServer.address") instanceof String s
            && !s.isEmpty()
        ? s
        : null;
  }

  boolean attachable() {
    return !(entries.get("sun.rt.jvmCapabilities") instanceof String caps) || !caps.startsWith("0");
  }

  OptionalLong vmCreationTimeMs() {
    return entries.get("sun.rt.createVmBeginTime") instanceof Long created && created > 0
        ? OptionalLong.of(created)
        : OptionalLong.empty();
  }

  static PerfData parse(byte[] b) throws IOException {
    if (b.length < PROLOGUE_SIZE) {
      throw new IOException("Perf data file is too short");
    }
    ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
    if (bb.getInt(0) != MAGIC) {
      throw new IOException("Not a perf data file");
    }
    bb.order(b[4] == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    @Var long offset = bb.getInt(24);
    int count = bb.getInt(28);
    Map<String, Object> entries = new HashMap<>();
    for (int i = 0; i < count; i++) {
      if (offset < 0 || offset > b.length - ENTRY_HEADER_SIZE) {
        break;
      }
      int entry = (int) offset;
      int length = bb.getInt(entry);
      if (length < ENTRY_HEADER_SIZE || length > b.length - entry) {
        break;
      }
      int end = entry + length;
      String name = cString(b, entry + (long) bb.getInt(entry + 4), end);
      int vectorLength = bb.getInt(entry + 8);
      byte type = b[entry + 12];
      long data = entry + (long) bb.getInt(entry + 16);
      if (type == 'B' && vectorLength > 0) {
        entries.put(name, cString(b, data, Math.min(end, data + vectorLength)));
      } else if (type == 'J' && vectorLength == 0 && data >= entry && data + 8 <= end) {
        entries.put(name, bb.getLong((int) data));
      }
      offset = end;
    }
    return new PerfData(entries);
  }

  private static String cString(byte[] b, long from, long to) {
    if (from < 0 || from >= to) {
      return "";
    }
    int start = (int) from;
    @Var int end = start;
    while (end < to && b[end] != 0) {
      end++;
    }
    return new String(b, start, end - start, StandardCharsets.UTF_8);
  }
}
