package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.track.ChangeDetails;
import com.non.docx.core.api.track.TextChangeDetails;
import com.non.docx.core.api.track.TrackedChange;
import com.non.docx.core.api.track.TrackedChangeLocation;
import com.non.docx.core.api.track.TrackedChangeSegment;
import com.non.docx.core.api.track.TrackedChanges;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;

/**
 * 演示文档修订(tracked changes)的<b>只读取消费</b>。
 *
 * <p>nondocx 当前已封装修订的<b>读取</b>能力(开关状态、按文档顺序枚举、按稳定 id 获取单条),但<b>尚未</b>封装创作修订 的 API(属 {@code
 * authoring} 子任务)。因此本示例分两步:
 *
 * <ol>
 *   <li><b>造样例</b>:用 XmlBeans 手搓一份带修订标记的 docx——直接写 {@code <w:ins>} / {@code <w:del>} 等 OOXML 结构。
 *       这一段是「绕过 nondocx 的 POI 直接操作」,仅用于造出可演示的真实修订文档;真实业务里这样的文档来自 Word/Office。
 *   <li><b>读取演示</b>:用 nondocx 的 {@link Document#trackedChanges()} 读取开关、枚举修订、按 id 取单条,把结果打印出来。
 * </ol>
 *
 * <p><b>OOXML 教学:修订长什么样。</b> 一个带修订的段落,其 {@code document.xml} 大致是:
 *
 * <pre>{@code
 * <w:p>
 *   <w:r><w:t>原有文本</w:t></w:r>
 *   <w:ins w:id="1" w:author="non" w:date="2026-06-18T10:00:00Z">
 *     <w:r><w:t>新增的文本</w:t></w:r>
 *   </w:ins>
 *   <w:del w:id="2" w:author="non" w:date="...">
 *     <w:r><w:delText>被删除的文本</w:delText></w:r>     <!-- 注意 del 用 delText,不是 t -->
 *   </w:del>
 * </w:p>
 * }</pre>
 *
 * <p>修订标记是带 {@code author}/{@code date}/{@code id} 属性的容器元素;开关在 {@code settings.xml} 的 {@code
 * <w:trackChanges/>}。nondocx 把「读开关 + 按文档顺序找修订节点 + 解析为领域视图」收进一个统一门面,对外 POI-free。
 */
public final class TrackedChangesExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("tracked-changes-example.docx");
    output.toFile().getParentFile().mkdirs();

    // ===== 第 1 步:造一份带修订的样例 docx(用 XmlBeans 直接写 OOXML)=====
    System.out.println("=== 第 1 步:造样例文档(带修订标记)===");
    Calendar when = new Calendar.Builder().setDate(2026, 5, 18).setTimeOfDay(10, 0, 0).build();
    try (Document doc = Docx.create()) {
      XWPFDocument poi = doc.raw();
      // 开启修订记录开关(等同 Word 里勾选「修订」)。nondocx 目前只读不写,故走 raw()。
      poi.getSettings().setTrackRevisions(true);

      CTBody body = poi.getDocument().getBody();

      // 段落 0:一段文字 + 一条插入 + 一条删除。
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p0 = body.addNewP();
      CTR baseRun0 = p0.addNewR();
      baseRun0.addNewT().setStringValue("这段话被审阅过:");
      addIns(p0, "1", "审阅者甲", "这是新增的内容", when);
      addDel(p0, "2", "审阅者甲", "这是被删除的内容");

      // 段落 1:另一条插入,演示跨段落的文档顺序。
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p1 = body.addNewP();
      addIns(p1, "3", "审阅者乙", "第二段也有插入", when);

      // 一个表格,其单元格里有修订,演示 location 能穿透 table → row → cell → paragraph。
      CTTbl tbl = body.addNewTbl();
      CTRow tr = tbl.addNewTr();
      CTTc tc = tr.addNewTc();
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP cellP = tc.addNewP();
      addIns(cellP, "4", "审阅者乙", "单元格内的修订", when);

      doc.save(output);
      System.out.println("已保存样例: " + output.toAbsolutePath());
    }

    // ===== 第 2 步:用 nondocx 读取并演示 =====
    System.out.println();
    System.out.println("=== 第 2 步:用 nondocx 读取修订 ===");
    try (Document doc = Docx.open(output)) {
      TrackedChanges tracked = doc.trackedChanges();

      // (a) 开关状态
      System.out.println("[enabled]  开启修订记录? " + tracked.enabled());

      // (b) 按文档顺序枚举全部修订
      List<TrackedChange> list = tracked.list();
      System.out.println("[list]      共 " + list.size() + " 条修订(按文档顺序):");
      for (int i = 0; i < list.size(); i++) {
        TrackedChange change = list.get(i);
        System.out.println("  #" + (i + 1) + " " + describe(change));
      }

      // (c) 按稳定 id 取单条(进程内稳定,可用于后续 accept/reject 复用)
      if (!list.isEmpty()) {
        String id = list.get(0).id();
        TrackedChange byId = tracked.get(id);
        System.out.println("[get(id)]   按 id 取回首条: " + describe(byId));
      }

      // (d) 位置 path 的可读形式(注意:toString 仅用于显示,不是稳定公共契约)
      System.out.println();
      System.out.println("=== 位置 path 演示 ===");
      System.out.println("  正文段落修订: " + pathString(list.get(0).location()));
      System.out.println("  表格单元格修订: " + pathString(list.get(3).location()));
    }
  }

  /** 把一条修订格式化为一行可读描述。 */
  private static String describe(TrackedChange change) {
    StringBuilder sb = new StringBuilder();
    sb.append("type=").append(change.type());
    sb.append(", family=").append(change.family());
    sb.append(", author=\"").append(change.author()).append("\"");
    if (change.date() != null) {
      sb.append(", date=")
          .append(change.date().get(Calendar.YEAR))
          .append('-')
          .append(pad(change.date().get(Calendar.MONTH) + 1))
          .append('-')
          .append(pad(change.date().get(Calendar.DAY_OF_MONTH)));
    }
    // details:这里只演示文本类(其它 family 由 advanced-types 子任务补齐)
    ChangeDetails details = change.details();
    if (details instanceof TextChangeDetails) {
      sb.append(", text=\"").append(((TextChangeDetails) details).text()).append("\"");
    }
    return sb.toString();
  }

  /** 把 location 的 segment 序列渲染为 {@code body[0] > paragraph[1] > ...} 形式。 */
  private static String pathString(TrackedChangeLocation location) {
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

  private static String pad(int n) {
    return n < 10 ? "0" + n : String.valueOf(n);
  }

  // ---------- 造样例用的 OOXML 辅助(因 nondocx 尚无创作修订的 API)----------

  /** 在段落里加一条 {@code <w:ins>},内含一个带 {@code <w:t>} 的 run。 */
  private static void addIns(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p,
      String id,
      String author,
      String text,
      Calendar date) {
    CTRunTrackChange ins = p.addNewIns();
    ins.setId(new java.math.BigInteger(id));
    ins.setAuthor(author);
    ins.setDate(date);
    CTR r = ins.addNewR();
    r.addNewT().setStringValue(text);
  }

  /** 在段落里加一条 {@code <w:del>},内含一个带 {@code <w:delText>} 的 run(删除用 delText)。 */
  private static void addDel(
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP p,
      String id,
      String author,
      String text) {
    CTRunTrackChange del = p.addNewDel();
    del.setId(new java.math.BigInteger(id));
    del.setAuthor(author);
    CTR r = del.addNewR();
    r.addNewDelText().setStringValue(text);
  }

  private TrackedChangesExample() {}
}
