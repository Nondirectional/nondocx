package com.non.docx.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Demo trace 的追加式 JSONL 日志。API key 等敏感键在落盘前剔除。 */
final class TraceJournal {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path file;

  TraceJournal(Path workDir) {
    this.file = workDir.resolve("trace.jsonl");
  }

  synchronized void append(Map<String, Object> event) {
    try {
      Files.createDirectories(file.getParent());
      Map<String, Object> safe = new LinkedHashMap<>(event);
      safe.remove("apiKey");
      safe.remove("authorization");
      safe.remove("Authorization");
      safe.put("timestamp", System.currentTimeMillis());
      Files.writeString(
          file,
          JSON.writeValueAsString(safe) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new RuntimeException("写 trace journal 失败", e);
    }
  }

  synchronized String readAll() {
    try {
      return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      throw new RuntimeException("读取 trace journal 失败", e);
    }
  }
}
