package com.non.docx.examples;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.comment.Comment;
import com.non.docx.core.api.comment.Comments;
import com.non.docx.core.api.text.Paragraph;
import java.nio.file.Path;
import java.util.List;

/**
 * 演示文档批注(comments)的<b>完整闭环</b>:创作范围批注 → 回复形成线程 → 落盘 → 重开 → 读回验证。
 *
 * <p>与 {@link TrackedChangesExample} 不同,批注的<b>创作 API 已完整封装</b>,本示例全程用 nondocx 领域 API (无需手搓
 * OOXML),演示真实业务里的典型用法。
 *
 * <p><b>OOXML 教学:批注长什么样。</b> 创作出的批注,其结构分离在多个 part:
 *
 * <pre>{@code
 * word/document.xml（正文锚点）:
 *   <w:p>
 *     <w:commentRangeStart w:id="0"/>     ← 批注范围起点（包住整段）
 *     <w:r>已有文本</w:r>
 *     <w:commentRangeEnd w:id="0"/>       ← 范围终点
 *     <w:r><w:commentReference w:id="0"/></w:r>   ← 引用 run
 *   </w:p>
 * word/comments.xml（批注正文）:
 *   <w:comment w:id="0" w:author="审阅者甲" w:date="..." w:initials="">
 *     <w:p w14:paraId="..." w:rsidR="..." w:rsidRDefault="...">   ← paraId/RSID 自动注入
 *       <w:r><w:t>这段需要补充背景</w:t></w:r>
 *     </w:p>
 *   </w:comment>
 * word/commentsExtended.xml（线程关系，回复才有）:
 *   <w15:commentEx w15:paraId="回复paraId" w15:paraIdParent="父批注paraId" w15:done="0"/>
 * word/people.xml（作者注册，@mention 提示）:
 *   <w15:person w15:author="审阅者甲">...</w15:person>
 * }</pre>
 *
 * <p>nondocx 把「建 comments.xml 条目 + 正文三锚点 + XmlCursor 定位 + 线程四 part 自维护 + 基础设施注入」全收进 {@code
 * internal/poi/},对外只暴露 {@link Paragraph#addComment} 与 {@link Comments#reply} 两个 POI-free 入口。
 * people.xml / paraId / RSID 在创作时<b>自动注入</b>,公共 API 无感。
 */
public final class CommentsExample {

  public static void main(String[] args) throws Exception {
    Path output = ExamplePaths.outputDir().resolve("comments-example.docx");
    output.toFile().getParentFile().mkdirs();

    // ===== 第 1 步:创建文档 + 创作范围批注 =====
    System.out.println("=== 第 1 步:创建文档并给段落加范围批注 ===");
    try (Document doc = Docx.create()) {
      // 两段正文,模拟一份待审阅的草稿
      doc.addParagraph("第一段:这是需要被评论的内容。");
      doc.addParagraph("第二段:另一段内容。");

      // 给第一段加一条范围批注（范围 = 整段,commentRangeStart 在段首、End 在段末）
      Paragraph p0 = doc.paragraph(0);
      Comment root = p0.addComment("审阅者甲", "这段需要补充背景说明");
      System.out.println("[addComment]  创建批注 id=" + root.id() + ", author=" + root.author());
      System.out.println("              范围:包住整段第一段");

      // ===== 第 2 步:回复批注,形成线程 =====
      System.out.println();
      System.out.println("=== 第 2 步:回复批注,形成线程 ===");
      Comment reply = doc.comments().reply(root.id(), "审阅者乙", "已补充,见第二段");
      System.out.println("[reply]       回复批注 id=" + reply.id() + ", author=" + reply.author());
      System.out.println(
          "              parentId="
              + reply.parentId().orElse("(根批注)")
              + " (指向根批注 "
              + root.id()
              + ")");

      doc.save(output);
      System.out.println();
      System.out.println("已保存: " + output.toAbsolutePath());
    }

    // ===== 第 3 步:重开文档,读回验证线程 =====
    System.out.println();
    System.out.println("=== 第 3 步:重开文档,读回批注与线程 ===");
    try (Document doc = Docx.open(output)) {
      Comments cs = doc.comments();

      // (a) 按文档顺序枚举全部批注
      List<Comment> list = cs.list();
      System.out.println("[list]        共 " + list.size() + " 条批注（按文档顺序）:");
      for (int i = 0; i < list.size(); i++) {
        Comment c = list.get(i);
        System.out.println("  #" + (i + 1) + " " + describe(c));
      }

      // (b) 按 id 取单条（id 跨会话稳定）
      if (!list.isEmpty()) {
        String firstId = list.get(0).id();
        Comment byId = cs.get(firstId);
        System.out.println();
        System.out.println("[get(id)]     按 id=" + firstId + " 取回首条: " + describe(byId));
      }

      // (c) 线程关系:找出回复批注,确认 parentId 指向根批注
      System.out.println();
      System.out.println("=== 线程关系验证 ===");
      list.stream()
          .filter(c -> c.parentId().isPresent())
          .forEach(
              c ->
                  System.out.println(
                      "  批注 \"" + c.text() + "\" 是回复,指向父批注 id=" + c.parentId().get()));
      long roots = list.stream().filter(c -> c.parentId().isEmpty()).count();
      System.out.println("  根批注数（parentId 为空）: " + roots);
    }

    // ===== 第 4 步:基础设施说明 =====
    System.out.println();
    System.out.println("=== 第 4 步:现代兼容基础设施（自动注入,无需手动）===");
    System.out.println("创作出的批注已自动包含:");
    System.out.println("  - word/people.xml      → author 注册（Word @mention 提示）");
    System.out.println("  - w14:paraId           → 段落身份标记（线程关系 key）");
    System.out.println("  - w:rsidR / settings.xml rsids → 修订会话标识（Word 合并修订对齐）");
    System.out.println("用 unzip 查看 " + output.toAbsolutePath() + " 可见上述 part/属性。");
    System.out.println();
    System.out.println("详见 docs/06-comments/04-infrastructure.md。");
  }

  /** 把一条批注格式化为一行可读描述。 */
  private static String describe(Comment c) {
    StringBuilder sb = new StringBuilder();
    sb.append("id=").append(c.id());
    sb.append(", author=\"").append(c.author()).append("\"");
    sb.append(", text=\"").append(c.text()).append("\"");
    if (c.parentId().isPresent()) {
      sb.append(" [回复 → 父批注 ").append(c.parentId().get()).append("]");
    } else {
      sb.append(" [根批注]");
    }
    if (c.paraId() != null) {
      sb.append(", paraId=").append(c.paraId());
    }
    return sb.toString();
  }

  private CommentsExample() {}
}
