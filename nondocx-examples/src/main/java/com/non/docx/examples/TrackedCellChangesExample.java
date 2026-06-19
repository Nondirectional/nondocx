package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.exception.UnsupportedFeatureException;
import com.non.docx.core.api.track.CellChangeDetails;
import com.non.docx.core.api.track.CellChangeKind;
import com.non.docx.core.api.track.TrackedChange;
import com.non.docx.core.api.track.TrackedChangeLocation;
import com.non.docx.core.api.track.TrackedChangeSegment;
import com.non.docx.core.api.track.TrackedChangeType;
import com.non.docx.core.api.track.TrackedChanges;
import java.nio.file.Path;
import java.util.List;
import javax.xml.namespace.QName;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrackChange;

/**
 * 演示表格单元格结构类修订({@code cellIns} / {@code cellDel} / {@code cellMerge})的<b>读取与 accept/reject</b>。
 *
 * <p>这是 tracked changes 项目里 cell 子任务的验收示例。nondocx 已封装修订单元格的<b>读</b>能力({@code cellIns}/{@code
 * cellDel}/{@code cellMerge} 都能被 {@code list()} 读回)与 {@code cellIns}/{@code cellDel} 的
 * <b>accept/reject</b> 能力;但<b>尚未</b>封装修创作侧 authoring(属后续子任务)。因此本示例分两步:
 *
 * <ol>
 *   <li><b>造样例</b>:用 XmlBeans 手搓一份带单元格结构修订标记的 docx——直接写 {@code <w:tcPr><w:cellIns/>} 等 OOXML
 *       结构。这一段绕过 nondocx 直接操作 POI,仅用于造出可演示的真实修订文档;真实业务里这样的文档来自 Word。
 *   <li><b>读取 + accept/reject 演示</b>:用 nondocx 的 {@link Document#trackedChanges()} 读回三种 cell 修订, 演示
 *       accept/reject 改变单元格存亡,以及 cellMerge 的 accept/reject 被诚实拒绝(抛异常)。
 * </ol>
 *
 * <p><b>OOXML 教学:单元格结构修订长什么样。</b> 单元格插入/删除/合并标记<b>嵌在 {@code <w:tcPr>}(单元格属性)里</b>, 是裸属性元素(只有
 * id/author/date,无 run、无文本),标记「这个单元格本身是被插入/删除/合并的」——表格<b>结构修订</b>, 不是单元格内文本的修订:
 *
 * <pre>{@code
 * <w:tbl><w:tr>
 *   <w:tc>
 *     <w:tcPr>
 *       <w:cellIns w:id="1" w:author="non"/>          <!-- 这个单元格是被插入的 -->
 *     </w:tcPr>
 *     <w:p><w:r><w:t>新插入的单元格</w:t></w:r></w:p>
 *   </w:tc>
 *   <w:tc>
 *     <w:tcPr>
 *       <w:cellDel w:id="2" w:author="non"/>          <!-- 这个单元格是被删除的 -->
 *     </w:tcPr>
 *     <w:p><w:r><w:t>将被删除</w:t></w:r></w:p>
 *   </w:tc>
 * </w:tr></w:tbl>
 * }</pre>
 *
 * <p><b>关键差异(与文本类 {@code ins}/{@code del} 对比)</b>:文本类修订标记的是 run 级文本增删,accept/reject 操作 run; 而 cell
 * 类标记的是「单元格本身的存亡」,accept/reject 操作<b>整个 {@code <w:tc>} 元素</b>。这正是本示例要演示的核心语义。
 *
 * <p><b>POI lite 的 dangling reference 坑(cellMerge)</b>:{@code cellMerge} 的 CT 类型 {@code
 * CTCellMergeTrackChange} 在 POI 精简 jar 里既无 Java 类(编译期 javac 拒绝 {@code getCellMerge()} 调用),也无
 * XmlBeans 的 {@code .xsb} schema 资源(运行期 {@code cur.getObject()} 会抛 {@code
 * SchemaTypeLoaderException})。因此造 cellMerge 样例和读取它都只能走纯 XmlCursor,不能碰类型化对象。详见 {@code poi-bridge.md}
 * N16。
 *
 * @see TrackedChangesExample 文本/移动类修订的基础读取示例
 */
public final class TrackedCellChangesExample {

  /** OOXML {@code w} 命名空间(造 cellMerge 裸元素与读属性时用)。 */
  private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("tracked-cell-changes-example.docx");
    output.toFile().getParentFile().mkdirs();

    // ===== 第 1 步:造一份带单元格结构修订的样例 docx =====
    System.out.println("=== 第 1 步:造样例文档(带 cellIns/cellDel/cellMerge)===");
    try (Document doc = Docx.create()) {
      XWPFDocument poi = doc.raw();
      poi.getSettings().setTrackRevisions(true);

      CTBody body = poi.getDocument().getBody();
      body.addNewP().addNewR().addNewT().setStringValue("下面的表格演示单元格结构类修订:");

      // 一张 1×3 的表格:三个单元格分别带 cellIns / cellDel / cellMerge。
      CTTbl tbl = body.addNewTbl();
      CTRow tr = tbl.addNewTr();

      // tc0:带 cellIns(这个单元格是被插入的)
      CTTc tc0 = tr.addNewTc();
      tc0.addNewP().addNewR().addNewT().setStringValue("新插入的单元格");
      addCellIns(tc0, "1", "审阅者甲");

      // tc1:带 cellDel(这个单元格是被删除的)
      CTTc tc1 = tr.addNewTc();
      tc1.addNewP().addNewR().addNewT().setStringValue("将被删除");
      addCellDel(tc1, "2", "审阅者甲");

      // tc2:带 cellMerge(两个单元格合并;CT 类型缺失,只能 XmlCursor 造裸元素)
      CTTc tc2 = tr.addNewTc();
      tc2.addNewP().addNewR().addNewT().setStringValue("合并的单元格");
      addCellMergeViaCursor(tc2, "3", "审阅者乙");

      doc.save(output);
      System.out.println("已保存样例: " + output.toAbsolutePath());
      System.out.println("(表格 1 行 3 列,每列各带一种单元格结构修订)");
    }

    // ===== 第 2 步:用 nondocx 读取三种 cell 修订 =====
    System.out.println();
    System.out.println("=== 第 2 步:用 nondocx 读取单元格结构修订 ===");
    try (Document doc = Docx.open(output)) {
      TrackedChanges tracked = doc.trackedChanges();
      List<TrackedChange> list = tracked.list();
      System.out.println("[list]      共 " + list.size() + " 条单元格结构修订:");
      for (int i = 0; i < list.size(); i++) {
        System.out.println("  #" + (i + 1) + " " + describeCell(list.get(i)));
      }
      System.out.println("(注意:location path 都停在 cell[?] —— 不含 paragraph,");
      System.out.println(" 因为 cell 修订挂在 tcPr,比单元格内段落高一层。)");
    }

    // ===== 第 3 步:演示 accept/reject 改变单元格存亡 =====
    System.out.println();
    System.out.println("=== 第 3 步:accept / reject 改变单元格存亡 ===");
    demonstrateAcceptReject(output);

    // ===== 第 4 步:演示 cellMerge 的 accept/reject 被诚实拒绝 =====
    System.out.println();
    System.out.println("=== 第 4 步:cellMerge 的 accept/reject 被诚实拒绝(边界演示)===");
    demonstrateCellMergeBoundary(output);
  }

  /** 演示:accept cellIns 保留单元格、reject cellDel 恢复单元格。每次重新打开样例避免互相干扰。 */
  private static void demonstrateAcceptReject(Path sample) throws Exception {
    // (a) accept cellIns:单元格插入生效 → 保留整个 <w:tc>,仅删标记。
    Path acceptOut = ExamplePaths.outputDir().resolve("tracked-cell-accept-cellIns.docx");
    try (Document doc = Docx.open(sample)) {
      TrackedChanges tracked = doc.trackedChanges();
      TrackedChange cellIns = findByType(tracked, TrackedChangeType.CELL_INS);
      System.out.println("[acceptCell] 对 cellIns(id=" + cellIns.id() + ") 执行 accept...");
      System.out.println("  语义:单元格插入生效,保留整个 <w:tc>,仅删 cellIns 标记。");
      tracked.acceptCell(cellIns.id());
      System.out.println("  操作后剩余修订: " + tracked.list().size() + " 条(cellIns 已消失)");
      doc.save(acceptOut);
      verifyTcCount(acceptOut, "accept cellIns 后", 3); // 三个 tc 都还在
    }

    // (b) reject cellDel:单元格删除被撤销 → 保留整个 <w:tc>,仅删标记。
    Path rejectOut = ExamplePaths.outputDir().resolve("tracked-cell-reject-cellDel.docx");
    try (Document doc = Docx.open(sample)) {
      TrackedChanges tracked = doc.trackedChanges();
      TrackedChange cellDel = findByType(tracked, TrackedChangeType.CELL_DEL);
      System.out.println();
      System.out.println("[rejectCell] 对 cellDel(id=" + cellDel.id() + ") 执行 reject...");
      System.out.println("  语义:删除被撤销,单元格恢复,保留整个 <w:tc>,仅删 cellDel 标记。");
      tracked.rejectCell(cellDel.id());
      System.out.println("  操作后剩余修订: " + tracked.list().size() + " 条(cellDel 已消失)");
      doc.save(rejectOut);
      verifyTcCount(rejectOut, "reject cellDel 后", 3); // tc1 还在(被恢复)
    }

    // (c) accept cellDel:单元格删除生效 → 移除整个 <w:tc>。
    Path acceptDelOut = ExamplePaths.outputDir().resolve("tracked-cell-accept-cellDel.docx");
    try (Document doc = Docx.open(sample)) {
      TrackedChanges tracked = doc.trackedChanges();
      TrackedChange cellDel = findByType(tracked, TrackedChangeType.CELL_DEL);
      System.out.println();
      System.out.println("[acceptCell] 对 cellDel(id=" + cellDel.id() + ") 执行 accept...");
      System.out.println("  语义:删除生效,移除整个 <w:tc>(含其内段落)。");
      tracked.acceptCell(cellDel.id());
      System.out.println("  操作后剩余修订: " + tracked.list().size() + " 条(cellDel 已消失)");
      doc.save(acceptDelOut);
      verifyTcCount(acceptDelOut, "accept cellDel 后", 2); // tc1 被移除,只剩 2 个 tc
    }
  }

  /** 演示:cellMerge 的 accept/reject 抛 UnsupportedFeatureException(CT 类型缺失,诚实拒绝)。 */
  private static void demonstrateCellMergeBoundary(Path sample) throws Exception {
    try (Document doc = Docx.open(sample)) {
      TrackedChanges tracked = doc.trackedChanges();
      TrackedChange cellMerge = findByType(tracked, TrackedChangeType.CELL_MERGE);
      System.out.println("找到 cellMerge: id=" + cellMerge.id());
      System.out.println("  它被读回为 UNCONFIRMED_MERGE(合并细节读不到,因 CT 类型缺失)。");
      System.out.println("  accept/reject 暂不支持(合并/拆分涉及相邻单元格 vMerge 恢复,结构风险高)。");

      System.out.println();
      System.out.println("[acceptCell] 尝试 accept cellMerge...");
      try {
        tracked.acceptCell(cellMerge.id());
        System.out.println("  (不应到达此处)");
      } catch (UnsupportedFeatureException e) {
        System.out.println("  ✓ 抛出 UnsupportedFeatureException,诚实拒绝:");
        System.out.println("    \"" + e.getMessage() + "\"");
      }

      System.out.println();
      System.out.println("  操作后文档未变:cellMerge 仍在(" + tracked.list().size() + " 条修订)。");
    }
  }

  /** 把一条 cell 修订格式化为一行可读描述。 */
  private static String describeCell(TrackedChange change) {
    StringBuilder sb = new StringBuilder();
    sb.append("type=").append(change.type());
    sb.append(", family=").append(change.family());
    sb.append(", author=\"").append(change.author()).append("\"");
    ChangeDetailsHelper.appendKind(sb, change);
    sb.append(", location=").append(pathString(change));
    return sb.toString();
  }

  /** 在 list 里按 type 找一条修订;找不到则抛 IllegalStateException(样例构造应保证存在)。 */
  private static TrackedChange findByType(TrackedChanges tracked, TrackedChangeType want) {
    return tracked.list().stream()
        .filter(c -> c.type() == want)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("样例里找不到 " + want + " 修订(样例构造有误)"));
  }

  /** 打开保存后的文档,核对表格第一行的 tc 数量是否符合预期(验证 accept/reject 的结构结果)。 */
  private static void verifyTcCount(Path file, String label, int expected) throws Exception {
    try (Document doc = Docx.open(file)) {
      CTRow tr = doc.raw().getDocument().getBody().getTblArray(0).getTrArray(0);
      int actual = tr.sizeOfTcArray();
      String mark = actual == expected ? "✓" : "✗(预期 " + expected + ")";
      System.out.println("  " + label + ",表格首行剩余 " + actual + " 个单元格 " + mark);
    }
  }

  /** 把 location 渲染为 {@code body[0] > table[0] > row[0] > cell[0]} 形式。 */
  private static String pathString(TrackedChange change) {
    TrackedChangeLocation location = change.location();
    List<TrackedChangeSegment> segs = location.segments();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segs.size(); i++) {
      if (i > 0) {
        sb.append(" > ");
      }
      TrackedChangeSegment s = segs.get(i);
      sb.append(s.kind().name().toLowerCase()).append('[').append(s.index()).append(']');
    }
    return sb.toString();
  }

  // ---------- 造样例用的 OOXML 辅助(nondocx 尚无 cell 修订创作 API)----------

  /**
   * 在 tc 的 tcPr 里加一个 {@code <w:cellIns>}(typed 路径,{@code CTTcPr.addNewCellIns()} 返回 {@code
   * CTTrackChange})。
   */
  private static void addCellIns(CTTc tc, String id, String author) {
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    CTTrackChange cellIns = tcPr.addNewCellIns();
    cellIns.setId(new java.math.BigInteger(id));
    cellIns.setAuthor(author);
  }

  /** 在 tc 的 tcPr 里加一个 {@code <w:cellDel>}(typed 路径)。 */
  private static void addCellDel(CTTc tc, String id, String author) {
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    CTTrackChange cellDel = tcPr.addNewCellDel();
    cellDel.setId(new java.math.BigInteger(id));
    cellDel.setAuthor(author);
  }

  /**
   * 用 XmlCursor 在 tc 的 tcPr 里插一个裸 {@code <w:cellMerge>} 元素。
   *
   * <p>因 {@code CTCellMergeTrackChange} 编译期不可达({@code addNewCellMerge()} 调用 javac 拒绝),只能用 XmlCursor
   * {@code beginElement} 直接造一个本地名为 {@code cellMerge} 的元素,再设 {@code w:id}/{@code w:author} 属性。这是
   * cellMerge fixture 的唯一构造方式。
   */
  private static void addCellMergeViaCursor(CTTc tc, String id, String author) {
    CTTcPr tcPr = tc.isSetTcPr() ? tc.getTcPr() : tc.addNewTcPr();
    XmlCursor cur = tcPr.newCursor();
    try {
      cur.toEndToken(); // 指向 tcPr 内部末尾
      cur.beginElement(QName.valueOf("{" + W_NS + "}cellMerge"));
      cur.toPrevSibling(); // 回到新建的 cellMerge 元素
      cur.insertAttributeWithValue(QName.valueOf("{" + W_NS + "}id"), id);
      cur.insertAttributeWithValue(QName.valueOf("{" + W_NS + "}author"), author);
    } finally {
      cur.dispose();
    }
  }

  private TrackedCellChangesExample() {}

  // ---------- 内部辅助:把 details 渲染(隔离 import,避免类型判断散落)----------

  /** 把 cell 修订的 details(kind)拼到描述里。 */
  private static final class ChangeDetailsHelper {
    static void appendKind(StringBuilder sb, TrackedChange change) {
      if (change.details() instanceof CellChangeDetails) {
        CellChangeKind kind = ((CellChangeDetails) change.details()).kind();
        sb.append(", kind=").append(kind);
      }
    }
  }
}
