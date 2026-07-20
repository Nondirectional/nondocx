package io.github.nondirectional.docx.core.api.toc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

/**
 * 目录（TOC）只读解析的验收测试。用 XmlBeans 手搓 TOC 域（确定性、不依赖 Word），验证 nondocx 的解析与往返保真。
 *
 * <p>这些测试也是 poi-bridge.md N11 的回归保护：TOC 域结构（fldChar/instrText/separate/end + 条目段落的 {@code
 * <w:hyperlink w:anchor=...>}）跨 save→reopen 必须能被正确解析。
 */
class TableOfContentsTest {

  @Test
  void parsesTocEntries(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("toc.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      buildTocField(poi, /* dirty= */ true);
      try (var out = Files2.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      TableOfContents toc = doc.toc();
      assertThat(toc).as("含 TOC 域的文档应返回非 null").isNotNull();
      assertThat(toc.dirty()).as("begin fldChar 上置了 dirty").isTrue();
      assertThat(toc.entries())
          .containsExactly(
              new TocEntry("第一章 引言", 1, "3", "_Toc100001"),
              new TocEntry("1.1 背景", 2, "4", "_Toc100002"));
      assertThat(toc.size()).isEqualTo(2);
    }
  }

  @Test
  void returnsNullWhenNoToc(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("no-toc.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      poi.createParagraph().createRun().setText("普通文档，没有目录");
      try (var out = Files2.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      assertThat(doc.toc()).isNull();
    }
  }

  /**
   * SDT 形态 TOC（较新 Word）：整个目录被包在 {@code <w:sdt>/<w:sdtContent>} 里，且首个条目段落本身承载 TOC 域的 begin，
   * 每个条目的可见内容在 CTP 级 {@code <w:hyperlink w:anchor=...>} 内。这是 {@code getParagraphs()} 看不到的形态—— 必须穿透
   * SDT 才能解析。真实样例：1072.docx。
   */
  @Test
  void parsesTocInsideSdtBlock(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("toc-sdt.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      buildSdtTocField(poi);
      try (var out = Files2.newOutputStream(file)) {
        poi.write(out);
      }
    }

    try (Document doc = Docx.open(file)) {
      TableOfContents toc = doc.toc();
      assertThat(toc).as("SDT 形态 TOC 也应被解析").isNotNull();
      // 即使这些段落藏在 <w:sdtContent> 里、getParagraphs() 看不见，穿透后应全部解析出来。
      assertThat(toc.entries())
          .containsExactly(
              new TocEntry("第一章 引言", 1, "3", "_Toc300001"),
              new TocEntry("1.1 背景", 2, "4", "_Toc300002"));
    }
  }

  @Test
  void tocSurvivesRoundTrip(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("toc-roundtrip.docx");
    try (XWPFDocument poi = new XWPFDocument()) {
      buildTocField(poi, /* dirty= */ false);
      try (var out = Files2.newOutputStream(file)) {
        poi.write(out);
      }
    }

    // 两次独立打开各自的 TableOfContents 应内容相等（活对象语义、不比较委托引用）。
    TableOfContents first;
    try (Document doc = Docx.open(file)) {
      first = doc.toc();
    }
    TableOfContents second;
    try (Document doc = Docx.open(file)) {
      second = doc.toc();
    }
    assertThat(second).isEqualTo(first);
    assertThat(second.hashCode()).isEqualTo(first.hashCode());
  }

  /**
   * 手搓一个最小 TOC 域：begin(dirty?) + instrText("TOC ...") + separate，接两个条目段落（TOC1/TOC2 样式， 内容在 CTP 级
   * {@code <w:hyperlink w:anchor=...>} 内：标题 + 制表符 + 页码），最后 end。
   *
   * <p>这是 Word 实际导出 TOC 的结构缩影，确定性构造，便于断言。
   */
  private static void buildTocField(XWPFDocument poi, boolean dirty) {
    XWPFParagraph p0 = poi.createParagraph();
    addFldChar(p0, STFldCharType.BEGIN, dirty);
    addInstr(p0, " TOC \\o \"1-3\" \\h \\z \\u ");
    addFldChar(p0, STFldCharType.SEPARATE, false);

    addEntry(poi, "TOC1", "第一章 引言", "3", "_Toc100001");
    addEntry(poi, "TOC2", "1.1 背景", "4", "_Toc100002");

    XWPFParagraph pe = poi.createParagraph();
    addFldChar(pe, STFldCharType.END, false);
  }

  /**
   * 手搓 SDT 形态 TOC：一个 {@code <w:sdt>}，其 {@code <w:sdtContent>} 内含：首段（TOC1 样式，自身承载 begin +
   * instrText("TOC...") + separate，且可见内容在 hyperlink 内 = 第一个条目）、第二个条目段、 收尾 end 段。结构与真实较新 Word 导出的
   * TOC 一致。
   */
  private static void buildSdtTocField(XWPFDocument poi) {
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody body =
        poi.getDocument().getBody();
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock sdt = body.addNewSdt();
    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentBlock content =
        sdt.addNewSdtContent();

    // 首段：TOC 域 begin + instrText + separate，同时是第一个条目（TOC1 样式，内容在 hyperlink 内）。
    CTP first = content.addNewP();
    first.addNewPPr().addNewPStyle().setVal("TOC1");
    addFldCharToCtp(first, STFldCharType.BEGIN, false);
    addInstrToCtp(first, " TOC \\o \"1-3\" \\h \\z \\u ");
    addFldCharToCtp(first, STFldCharType.SEPARATE, false);
    addEntryToCtp(first, "第一章 引言", "3", "_Toc300001");

    // 第二个条目段。
    CTP second = content.addNewP();
    second.addNewPPr().addNewPStyle().setVal("TOC2");
    addEntryToCtp(second, "1.1 背景", "4", "_Toc300002");

    // 收尾 end 段。
    CTP tail = content.addNewP();
    addFldCharToCtp(tail, STFldCharType.END, false);
  }

  /** 在一个 CTP 的 hyperlink 内加条目可见内容（标题 + 制表符 + 页码），带 anchor。 */
  private static void addEntryToCtp(CTP ctp, String title, String page, String anchor) {
    CTHyperlink hl = ctp.addNewHyperlink();
    hl.setAnchor(anchor);
    CTR titleRun = hl.addNewR();
    titleRun.addNewT().setStringValue(title);
    hl.addNewR().addNewTab();
    CTR pageRun = hl.addNewR();
    pageRun.addNewT().setStringValue(page);
  }

  private static void addFldCharToCtp(CTP ctp, STFldCharType.Enum type, boolean dirty) {
    CTR r = ctp.addNewR();
    CTFldChar fc = r.addNewFldChar();
    fc.setFldCharType(type);
    if (dirty) {
      fc.setDirty(true);
    }
  }

  private static void addInstrToCtp(CTP ctp, String instr) {
    ctp.addNewR().addNewInstrText().setStringValue(instr);
  }

  private static void addEntry(
      XWPFDocument poi, String style, String title, String page, String anchor) {
    XWPFParagraph p = poi.createParagraph();
    p.setStyle(style);
    CTP ctp = p.getCTP();
    CTHyperlink hl = ctp.addNewHyperlink();
    hl.setAnchor(anchor);
    CTR titleRun = hl.addNewR();
    titleRun.addNewT().setStringValue(title);
    hl.addNewR().addNewTab(); // 制表符
    CTR pageRun = hl.addNewR();
    pageRun.addNewT().setStringValue(page);
  }

  private static void addFldChar(XWPFParagraph p, STFldCharType.Enum type, boolean dirty) {
    XWPFRun run = p.createRun();
    CTFldChar fc = run.getCTR().addNewFldChar();
    fc.setFldCharType(type);
    if (dirty) {
      fc.setDirty(true);
    }
  }

  private static void addInstr(XWPFParagraph p, String instr) {
    XWPFRun run = p.createRun();
    run.getCTR().addNewInstrText().setStringValue(instr);
  }

  /** 小工具：避免测试方法签名声明 IOException。 */
  private static final class Files2 {
    static java.io.OutputStream newOutputStream(Path path) throws Exception {
      return java.nio.file.Files.newOutputStream(path);
    }
  }
}
