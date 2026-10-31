package io.github.nondirectional.docx.toolkit.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RenderPreview} 集成测试：本机 soffice 渲染 PDF/PNG（无 LibreOffice 环境自动跳过）。
 *
 * <p>错误路径（非法格式/启动失败）不依赖环境，恒跑。
 */
class RenderPreviewTest {

  /** PATH 里找 soffice；找不到时集成用例跳过（CI 无 LibreOffice）。 */
  private static boolean sofficeAvailable() {
    for (String dir : System.getenv("PATH").split(":")) {
      if (Files.isExecutable(Path.of(dir, "soffice"))) {
        return true;
      }
    }
    return false;
  }

  @Test
  void rendersPdfAndFirstPagePng(@TempDir Path tmp) throws Exception {
    assumeTrue(sofficeAvailable(), "本机无 soffice，跳过渲染集成测试");
    try (Document doc = Docx.create()) {
      doc.addParagraph("渲染预览测试");
      RenderPreview.RenderResult pdf = RenderPreview.render(doc, "pdf", tmp, "soffice", 90);
      assertThat(pdf.format()).isEqualTo("pdf");
      assertThat(pdf.file()).exists();
      assertThat(pdf.bytes()).isGreaterThan(0);
      assertThat(pdf.file().toString()).endsWith(".pdf");
      // 临时 docx 用完即删（目录里不残留 .docx）
      try (var listing = Files.list(tmp)) {
        assertThat(listing.anyMatch(p -> p.toString().endsWith(".docx"))).isFalse();
      }

      RenderPreview.RenderResult png = RenderPreview.render(doc, "png", tmp, "soffice", 90);
      assertThat(png.file()).exists();
      assertThat(png.bytes()).isGreaterThan(0);
      assertThat(png.file().toString()).endsWith(".png");
    }
  }

  @Test
  void rejectsUnknownFormat(@TempDir Path tmp) {
    try (Document doc = Docx.create()) {
      assertThatThrownBy(() -> RenderPreview.render(doc, "gif", tmp, "soffice", 10))
          .isInstanceOf(RenderPreview.RenderException.class)
          .hasMessageContaining("不支持的渲染格式");
    }
  }

  @Test
  void missingSofficeReportsLaunchFailure(@TempDir Path tmp) {
    try (Document doc = Docx.create()) {
      assertThatThrownBy(
              () -> RenderPreview.render(doc, "pdf", tmp, "/nonexistent/soffice-bin", 10))
          .isInstanceOf(RenderPreview.RenderException.class)
          .hasMessageContaining("启动 soffice 失败");
    }
  }

  @Test
  void roundTripsThroughReopenedDocument(@TempDir Path tmp) throws IOException {
    assumeTrue(sofficeAvailable(), "本机无 soffice，跳过渲染集成测试");
    Path file = tmp.resolve("src.docx");
    try (Document doc = Docx.create()) {
      doc.addParagraph("重开渲染");
      doc.save(file);
    }
    try (Document reopened = Docx.open(file)) {
      RenderPreview.RenderResult pdf = RenderPreview.render(reopened, "pdf", tmp, "soffice", 90);
      assertThat(pdf.bytes()).isGreaterThan(0);
    }
  }
}
