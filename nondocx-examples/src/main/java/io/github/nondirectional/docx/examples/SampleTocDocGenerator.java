package io.github.nondirectional.docx.examples;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

/**
 * 一次性生成器:产出 {@code src/main/resources/document/sample-toc-input.docx}——一份含目录(TOC)域的样例文档, 供 {@code
 * InteractiveDocxAgentExample} 演示 {@code read_toc}。
 *
 * <p><b>为什么不直接用 nondocx 的高级 API 造 TOC。</b> nondocx 只读 TOC、不创建(POI 无 Word 分页引擎, 算不出页码)。因此这里直接用
 * XmlBeans 手搓 TOC 域的 OOXML 结构(fldChar begin/separate/end + instrText + 条目段落的 {@code <w:hyperlink
 * w:anchor=...>}),与 Word 实际导出的 TOC 形态一致。确定性构造、不依赖 Word。
 *
 * <p><b>产物入库后即可删/留作 fixture</b>(见 design.md §5 的样例文档约定)。运行:
 *
 * <pre>{@code
 * mvn -q -pl nondocx-examples exec:java \
 *   -Dexec.mainClass=io.github.nondirectional.docx.examples.SampleTocDocGenerator
 * }</pre>
 */
public final class SampleTocDocGenerator {

  /**
   * 产物默认相对<b>模块根</b>的路径({@code nondocx-examples/src/main/resources/document/})。
   *
   * <p>这是一个<b>一次性</b>生成器(见 design.md §5:产物入库后即可删/留作 fixture)。 {@code exec:java} 的工作目录 因从 reactor
   * 根还是模块根运行而不同,故默认用相对路径 {@code nondocx-examples/src/...}——它从 reactor 根运行时正好命中 (这是 {@code
   * exec:java} 的常见工作目录)。需要写到别处时,把目标路径作为首个命令行参数传入。
   */
  private static final Path DEFAULT_OUTPUT =
      Path.of("nondocx-examples/src/main/resources/document/sample-toc-input.docx");

  public static void main(String[] args) throws Exception {
    Path output = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : DEFAULT_OUTPUT;
    Document doc = Docx.create();
    XWPFDocument poi = doc.raw();

    // === 目录域(TOC)===
    // begin + instrText("TOC ...") + separate
    XWPFParagraph beginPara = poi.createParagraph();
    addFldChar(beginPara, STFldCharType.BEGIN, /* dirty= */ false);
    addInstr(beginPara, " TOC \\o \"1-3\" \\h \\z \\u ");
    addFldChar(beginPara, STFldCharType.SEPARATE, false);

    // 目录条目:两级标题树
    addEntry(poi, "TOC1", "一、项目概述", "1", "_Toc200001");
    addEntry(poi, "TOC2", "1.1 背景", "1", "_Toc200002");
    addEntry(poi, "TOC2", "1.2 目标", "2", "_Toc200003");
    addEntry(poi, "TOC1", "二、技术方案", "3", "_Toc200004");
    addEntry(poi, "TOC2", "2.1 架构", "3", "_Toc200005");
    addEntry(poi, "TOC1", "三、总结", "5", "_Toc200006");

    // end
    XWPFParagraph endPara = poi.createParagraph();
    addFldChar(endPara, STFldCharType.END, false);

    Files.createDirectories(output.getParent());
    doc.save(output);
    System.out.println("已生成: " + output.toAbsolutePath());
  }

  /** 追加一个 TOC 条目段落: 给定 TOC 样式,内容包在 {@code <w:hyperlink w:anchor=...>} 内(标题 + 制表符 + 页码)。 */
  private static void addEntry(
      XWPFDocument poi, String style, String title, String page, String anchor) {
    XWPFParagraph p = poi.createParagraph();
    p.setStyle(style);
    CTP ctp = p.getCTP();
    CTHyperlink hl = ctp.addNewHyperlink();
    hl.setAnchor(anchor);
    CTR titleRun = hl.addNewR();
    titleRun.addNewT().setStringValue(title);
    hl.addNewR().addNewTab(); // 标题与页码之间的制表符
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

  private SampleTocDocGenerator() {}
}
