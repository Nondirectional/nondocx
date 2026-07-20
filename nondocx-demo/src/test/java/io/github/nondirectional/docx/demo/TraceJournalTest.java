package io.github.nondirectional.docx.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceJournalTest {

  @Test
  void appendsReplayableJsonlAndStripsCredentialFields(@TempDir Path temp) {
    TraceJournal journal = new TraceJournal(temp);

    journal.append(Map.of("type", "trace", "message", "ok", "apiKey", "secret"));

    String replay = journal.readAll();
    assertTrue(replay.contains("\"type\":\"trace\""));
    assertFalse(replay.contains("secret"));
  }
}
