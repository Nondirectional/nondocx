import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 探针：实证 move 创作/accept 各阶段 live view 的 XmlValueDisconnectedException 时序。 */
public class MoveCrashProbe {
  static void read(Document doc, String stage, int i) {
    try {
      System.out.println(stage + " 段" + i + " text: [" + doc.paragraph(i).text() + "]");
    } catch (Throwable t) {
      Throwable root = t;
      while (root.getCause() != null) root = root.getCause();
      System.out.println(
          stage + " 读段" + i + " 崩: " + root.getClass().getSimpleName() + ": " + root.getMessage());
    }
  }

  public static void main(String[] args) throws Exception {
    Path file = Files.createTempFile("probe-seed", ".docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph().addRun("被移走的文字");
      doc.addParagraph("目标段");
      doc.save(file);
    }
    try (Document doc = Docx.open(file)) {
      read(doc, "move前", 0);
      read(doc, "move前", 1);

      var moved =
          doc.paragraph(1)
              .moveRunsFrom("probe", doc.paragraph(0), List.of(doc.paragraph(0).runs().get(0)));
      System.out.println("moveRunTracked 完成, moved runs=" + moved.size());

      read(doc, "move后", 0);
      read(doc, "move后", 1);

      var tc = doc.trackedChanges();
      var list = tc.list();
      System.out.println("修订数=" + list.size());
      var to =
          list.stream()
              .filter(c -> c.type().name().equals("MOVE_TO"))
              .findFirst()
              .orElseThrow();
      System.out.println("accept 目标 id=" + to.id());
      tc.accept(to.id());
      System.out.println("accept 完成");

      for (int i = 0; i < doc.paragraphs().size(); i++) {
        read(doc, "accept后", i);
      }

      // 模拟 ids 列表里的第二端：move_from 已消失
      var fromId =
          list.stream()
              .filter(c -> c.type().name().equals("MOVE_FROM"))
              .findFirst()
              .orElseThrow()
              .id();
      try {
        tc.accept(fromId);
      } catch (Throwable t) {
        System.out.println("第二端 move_from 再 accept: " + t.getClass().getSimpleName() + ": " + t.getMessage());
      }

      Path out = Files.createTempFile("probe-out", ".docx");
      doc.save(out);
      try (Document r = Docx.open(out)) {
        for (int i = 0; i < r.paragraphs().size(); i++) {
          read(r, "reopen", i);
        }
        System.out.println("reopen 修订数=" + r.trackedChanges().list().size());
      }
    }
  }
}
