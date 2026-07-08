package com.non.docx.core.internal.poi;

import javax.xml.namespace.QName;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSettings;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>批注(comments)创作路径的<b>现代 Word 兼容基础设施</b>统一入口:把 nondocx 产出的批注补齐三项「锦上添花」元数据, 让 Word
 * 审阅面板显示完整作者信息、@mention 提示、合并修订对齐。缺了这些批注仍能用(子任务 1-3 已保证基本可用), 但 Word 体验打折。
 *
 * <p><b>三项基础设施。</b>
 *
 * <ul>
 *   <li><b>people.xml</b>({@code w15} 命名空间)——把批注 author 注册为 {@code <w15:person>},Word 用它做 @mention
 *       提示与 作者身份显示(否则显示「未知作者」)。
 *   <li><b>w14:paraId</b>——给批注内段落分配唯一 paraId,线程关系靠它(reply-threads 子任务的 paraIdParent 链的 key)。
 *   <li><b>RSID</b>(修订会话标识)——给创作产出的 {@code <w:p>}/{@code <w:r>} 标 {@code w:rsidR}/{@code
 *       w:rsidRDefault}, Word「合并修订」用它对齐同一编辑会话的变更。
 * </ul>
 *
 * <p><b>OOXML / POI / nondocx 三层。</b>
 *
 * <ul>
 *   <li><b>OOXML</b>:
 *       <pre>{@code
 * word/people.xml (w15 命名空间):
 *   <w15:people>
 *     <w15:person w15:author="审阅者甲">
 *       <w15:presenceInfo w15:providerId="None" w15:userId="审阅者甲"/>
 *     </w15:person>
 *   </w15:people>
 *
 * 批注内段落的 w14:paraId (w14 命名空间):
 *   <w:p w14:paraId="0A1B2C3D">...</w:p>
 *
 * RSID:
 *   word/settings.xml:
 *     <w:settings><w:rsids>
 *       <w:rsidRoot w:val="07DC5ECB"/>
 *       <w:rsid w:val="07DC5ECB"/>
 *     </w:rsids></w:settings>
 *   节点级(批注创作产出的 <w:p>/<w:r>):
 *     <w:p w:rsidR="07DC5ECB" w:rsidRDefault="07DC5ECB" ...>
 *     <w:r w:rsidR="07DC5ECB">
 *
 * }</pre>
 *   <li><b>POI</b>:三项全部<b>无便捷 API</b>。people.xml POI 无 Java 类(复用 N23 的 OPC part 自维护模式);paraId 无
 *       自动注入(XmlCursor setAttributeText);RSID 的 {@code CTDocRsids} 是 dangling reference(lite jar 缺
 *       class 文件,typed 访问器 {@code getRsids()} 运行期抛 {@code ClassNotFoundException},与 N16 同型),故走
 *       XmlCursor 操作 settings.xml 原始 XML。
 *   <li><b>nondocx</b>:三项脏活全收进本类(同包 {@code internal/poi}),公共 API 无感——用户调 {@code addComment}/ {@code
 *       reply} 不变,基础设施自动注入。
 * </ul>
 *
 * <p><b>RSID 的文档级单例(design §5)。</b> RSID 持久化在 {@code settings.xml} 的 {@code
 * <w:rsids>/<w:rsidRoot>}, {@link #documentRsid} 首次调用时生成并注册,后续调用读回——故 {@code save→reopen} 后仍是同一个
 * RSID(真正的「文档级」), 同一文档多次创作的节点标同一个 RSID(Word 合并修订语义:同一会话)。
 *
 * <p><b>幂等与防御式(prd R5)。</b> people.xml 的 author 精确匹配去重(AC5);RSID 的 rsids 段检查已存在;paraId 每次新随机
 * (创作路径每次产出新节点,天然不冲突)。settings.xml/people.xml 操作失败时<b>防御式降级</b>,不阻断主创作流程(批注正文 仍完整写出)。
 *
 * <p><b>与 tracked-changes 的隔离(AC6)。</b> 本类虽设计为可复用,但仅被 comments 创作路径({@code addComment} + {@code
 * reply}) 调用。tracked-changes 的 {@code TrackedChangeNodes} <b>不</b>接入本类——避免改动已稳定的 track 包(父任务 Q4
 * 约束)。
 */
public final class AuthoringInfra {

  private AuthoringInfra() {}

  // ---------- paraId ----------

  /**
   * 生成 8 位大写十六进制 paraId,范围 {@code [1, 0x7FFFFFFE]}(OOXML 约束:必须 {@code < 0x7FFFFFFF},对照 docx skill
   * {@code _generate_hex_id})。
   *
   * <p>复用 {@link CommentExtendedParts#randomHexId}。paraId 不查重(prd Q2):8 位 hex 空间 ~2³¹,单文档批注数远低于此,
   * 冲突概率可忽略;查重需全扫文档所有 paraId,成本不值。
   */
  public static String newParaId() {
    return CommentExtendedParts.randomHexId();
  }

  /**
   * 给段落的 {@code <w:p>} 设 {@code w14:paraId} 属性(XmlCursor setAttributeText)。
   *
   * <p>w14 命名空间:{@code http://schemas.microsoft.com/office/word/2010/wordml}。paraId 是线程关系链的 key
   * (reply-threads 子任务的 paraIdParent 指向它)。
   *
   * <p>本方法从 reply-threads 的 {@code CommentNodes.setParagraphParaId}(私有)提升为 public,逻辑不变——把 paraId
   * 注入收敛到本类统一入口。设失败防御式不抛(paraIdParent 链可能断,但 comments.xml 正文仍完整)。
   *
   * @param paragraph 目标段落(不能为 {@code null})
   * @param paraId paraId 值(不能为 {@code null})
   */
  public static void setParaId(XWPFParagraph paragraph, String paraId) {
    java.util.Objects.requireNonNull(paragraph, "paragraph");
    java.util.Objects.requireNonNull(paraId, "paraId");
    try {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = paragraph.getCTP();
      XmlCursor cur = ctp.newCursor();
      try {
        cur.setAttributeText(
            QName.valueOf("{http://schemas.microsoft.com/office/word/2010/wordml}paraId"), paraId);
      } finally {
        cur.dispose();
      }
    } catch (RuntimeException e) {
      // 设失败不致命:paraIdParent 链可能断,但 comments.xml 正文仍完整(prd R5 防御式)
    }
  }

  // ---------- people.xml ----------

  // people.xml 的 part 路径 / content type / relationship type
  private static final String PEOPLE_NAME = "/word/people.xml";
  private static final String PEOPLE_CT =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.people+xml";
  private static final String PEOPLE_REL =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/people";
  private static final String NS_W15 = "http://schemas.microsoft.com/office/word/2012/wordml";

  /**
   * 把 author 注册到 {@code people.xml}(幂等:已存在不重复加,AC5)。
   *
   * <p>OOXML 形态:
   *
   * <pre>{@code
   * <w15:people xmlns:w15="...">
   *   <w15:person w15:author="审阅者甲">
   *     <w15:presenceInfo w15:providerId="None" w15:userId="审阅者甲"/>
   *   </w15:person>
   * </w15:people>
   * }</pre>
   *
   * <p>算法(design §3.2):复用 N23 的 OPC part 自维护模式({@link CommentExtendedParts#ensurePart} 幂等建 part +
   * relationship)+ DOM 读-改-写(扫现有 {@code <w15:person w15:author=..>},author 精确匹配跳过,不存在则追加)。author
   * XML 转义防注入(DOM {@code setAttribute} 自动转义)。presenceInfo 用占位 {@code providerId="None"}(docx skill
   * 同款, prd Out of Scope:不做真实身份服务集成)。
   *
   * <p>幂等口径(prd Q3):author 去重用<b>精确字符串匹配</b>(不 normalize)——author 是用户显式传入的标识,normalize
   * (大小写/空格)会改变语义。
   *
   * <p><b>防御式。</b> people.xml part 不存在/创建失败、DOM 解析失败时,注入跳过不阻断主创作流程(prd R5)。
   *
   * @param document POI 文档(不能为 {@code null})
   * @param author 要注册的 author(不能为 {@code null})
   */
  public static void registerAuthor(XWPFDocument document, String author) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(author, "author");
    try {
      PackagePart part =
          CommentExtendedParts.ensurePart(
              document, PEOPLE_NAME, PEOPLE_CT, PEOPLE_REL, "w15", NS_W15, "people");
      Document dom = CommentExtendedParts.readOrCreateDom(part, "w15", NS_W15, "people");
      Element root = dom.getDocumentElement();
      // 幂等:扫现有 person,author 精确匹配则跳过(AC5)
      if (personExists(root, author)) {
        return;
      }
      // 不存在则追加 <w15:person w15:author=..><w15:presenceInfo .../></w15:person>
      Element person = dom.createElementNS(NS_W15, "w15:person");
      person.setAttributeNS(NS_W15, "w15:author", author);
      Element presence = dom.createElementNS(NS_W15, "w15:presenceInfo");
      presence.setAttributeNS(NS_W15, "w15:providerId", "None");
      presence.setAttributeNS(NS_W15, "w15:userId", author);
      person.appendChild(presence);
      root.appendChild(person);
      CommentExtendedParts.writeDom(part, dom);
    } catch (RuntimeException e) {
      // people.xml 维护失败不阻断主创作流程:批注正文仍完整写出(prd R5 防御式)
    }
  }

  /** 扫 {@code <w15:people>} 的 {@code <w15:person>} 子,author 精确匹配则返回 true。 */
  private static boolean personExists(Element root, String author) {
    NodeList persons = root.getElementsByTagNameNS(NS_W15, "person");
    for (int i = 0; i < persons.getLength(); i++) {
      Element p = (Element) persons.item(i);
      String existing = p.getAttributeNS(NS_W15, "author");
      if (existing.isEmpty()) {
        // 兼容非命名空间前缀的 DOM 读法
        existing = p.getAttribute("w15:author");
      }
      if (author.equals(existing)) {
        return true;
      }
    }
    return false;
  }

  // ---------- RSID ----------

  // w 命名空间 URI(w 命名空间字面量已在 CommentNodes 内联重复,见 N18 末尾 code smell 记录;此处再内联一处)
  private static final String NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  /**
   * 生成 8 位大写十六进制 RSID(修订会话标识)。
   *
   * <p>RSID 无 paraId 的 {@code < 0x7FFFFFFF} 约束(OOXML spec 对 RSID 范围宽松),但为统一性仍用 8 位 hex。复用 {@link
   * CommentExtendedParts#randomHexId}({@code [1, 0x7FFFFFFE]} 范围,跨会话也安全)。
   */
  public static String newRsid() {
    return CommentExtendedParts.randomHexId();
  }

  /**
   * 返回文档级 RSID 单例:从 {@code settings.xml} 的 {@code <w:rsids>/<w:rsidRoot>} 读;不存在则生成并注册。
   *
   * <p><b>文档级单例语义(design §5)。</b> RSID 持久化在 settings.xml,故 {@code save→reopen} 后仍是同一个 RSID。
   * 同一文档多次创作(addComment/reply)读回同一个 RSID;不同文档(不同 settings.xml)概率上不同。这让 Word「合并修订」 能正确对齐同一编辑会话的变更。
   *
   * <p><b>POI 的坑(N16 同型)。</b> {@code CTSettings.getRsids()} 声明返回 {@code CTDocRsids},但 lite jar 缺该
   * class 文件(dangling reference),运行期抛 {@code ClassNotFoundException}。故走 {@link XmlCursor} 操作
   * settings.xml 原始 XML: 读 {@code <w:rsids>} 段,取 {@code <w:rsidRoot w:val=..>};不存在则建 {@code
   * <w:rsids>} + {@code rsidRoot} + {@code rsid}。
   *
   * <p><b>防御式。</b> settings.xml 缺失/操作失败时返回一个临时 {@link #newRsid()}(不持久化,不阻断)。
   *
   * @param document POI 文档(不能为 {@code null})
   * @return 当前文档的 RSID(8 位 hex)
   */
  public static String documentRsid(XWPFDocument document) {
    java.util.Objects.requireNonNull(document, "document");
    try {
      CTSettings settings = document.getSettings().getCTSettings();
      if (settings == null) {
        return newRsid();
      }
      XmlCursor cur = settings.newCursor();
      try {
        // 1) 先尝试读已有 <w:rsids>/<w:rsidRoot w:val=..>
        String existing = readRsidRoot(cur);
        if (existing != null) {
          return existing;
        }
        // 2) 不存在:生成新 RSID,建 rsids 段(rsidRoot + rsid),返回
        String rsid = newRsid();
        registerRsid(settings, rsid);
        return rsid;
      } finally {
        cur.dispose();
      }
    } catch (RuntimeException e) {
      // settings.xml 缺失/操作失败:返回临时 RSID,不阻断主创作流程(prd R5)
      return newRsid();
    }
  }

  /**
   * 从 {@code <w:settings>} 的 {@code <w:rsids>/<w:rsidRoot w:val=..>} 读 RSID;不存在返回 {@code
   * null}。cursor 指向 settings。
   */
  private static String readRsidRoot(XmlCursor settingsCur) {
    // settingsCur 指向 <w:settings>,下钻找 <w:rsids>
    settingsCur.push();
    try {
      if (!settingsCur.toFirstChild()) {
        return null;
      }
      do {
        String local = settingsCur.getName() == null ? "" : settingsCur.getName().getLocalPart();
        if ("rsids".equals(local)) {
          // 下钻 rsids 找 rsidRoot
          settingsCur.push();
          if (settingsCur.toFirstChild()) {
            do {
              String childLocal =
                  settingsCur.getName() == null ? "" : settingsCur.getName().getLocalPart();
              if ("rsidRoot".equals(childLocal)) {
                return settingsCur.getAttributeText(QName.valueOf("{" + NS_W + "}val"));
              }
            } while (settingsCur.toNextSibling());
          }
          settingsCur.pop();
          return null;
        }
      } while (settingsCur.toNextSibling());
      return null;
    } finally {
      settingsCur.pop();
    }
  }

  /**
   * 把 RSID 注册到 {@code <w:settings>} 的 {@code <w:rsids>} 段(幂等):不存在则建 {@code <w:rsids>} + {@code
   * <w:rsidRoot w:val=rsid/>} + {@code <w:rsid w:val=rsid/>}。
   *
   * <p>schema 顺序:rsids 在 compat 之后,但 Word 宽容,追加到 settings 末尾也可。
   *
   * <p><b>XmlCursor 语义(实现期探针确认)。</b> {@code beginElement} 后 cursor 停在新元素的 <b>END</b>(非 START);
   * {@code toEndToken} 配合 {@code beginElement} 在空容器里插子。算法:
   *
   * <ol>
   *   <li>cursor 从 {@code <w:settings>} START → {@code toEndToken} 到 settings END。
   *   <li>{@code beginElement(rsids)}:插 {@code <w:rsids>}(settings 末尾子),cursor 在 rsids END。
   *   <li>{@code beginElement(rsidRoot)}:在 rsids END 前插(成为 rsids 子),cursor 在 rsidRoot END,设 val。
   *   <li>{@code toNextToken} 从 rsidRoot END 移到 rsids END,{@code beginElement(rsid)} 插第二个子,设 val。
   * </ol>
   */
  private static void registerRsid(CTSettings settings, String rsid) {
    XmlCursor cur = settings.newCursor();
    try {
      cur.toEndToken(); // <w:settings> END
      cur.beginElement(QName.valueOf("{" + NS_W + "}rsids")); // 插 rsids,cursor 在 rsids END
      cur.beginElement(QName.valueOf("{" + NS_W + "}rsidRoot")); // rsids 首子,cursor 在 rsidRoot END
      cur.insertAttributeWithValue(QName.valueOf("{" + NS_W + "}val"), rsid);
      cur.toNextToken(); // rsidRoot END → rsids END
      cur.beginElement(QName.valueOf("{" + NS_W + "}rsid")); // rsids 第二子
      cur.insertAttributeWithValue(QName.valueOf("{" + NS_W + "}val"), rsid);
    } finally {
      cur.dispose();
    }
  }

  /**
   * 给创作产出的段落设 {@code w:rsidR} + {@code w:rsidRDefault}(XmlCursor setAttributeText)。
   *
   * <p>设失败防御式不抛(prd R5)。RSID 是属性,不影响子元素顺序(既有 CommentsAuthoringTest 结构断言不断言属性)。
   *
   * @param paragraph 目标段落(不能为 {@code null})
   * @param rsid RSID 值(不能为 {@code null})
   */
  public static void stampRsid(XWPFParagraph paragraph, String rsid) {
    java.util.Objects.requireNonNull(paragraph, "paragraph");
    java.util.Objects.requireNonNull(rsid, "rsid");
    try {
      org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = paragraph.getCTP();
      XmlCursor cur = ctp.newCursor();
      try {
        cur.setAttributeText(QName.valueOf("{" + NS_W + "}rsidR"), rsid);
        cur.setAttributeText(QName.valueOf("{" + NS_W + "}rsidRDefault"), rsid);
      } finally {
        cur.dispose();
      }
    } catch (RuntimeException e) {
      // RSID 设失败不阻断主创作流程(prd R5)
    }
  }

  /**
   * 给创作产出的 run 设 {@code w:rsidR}(XmlCursor setAttributeText)。
   *
   * @param r 目标 run 的 CTR(不能为 {@code null})
   * @param rsid RSID 值(不能为 {@code null})
   */
  public static void stampRsid(CTR r, String rsid) {
    java.util.Objects.requireNonNull(r, "r");
    java.util.Objects.requireNonNull(rsid, "rsid");
    try {
      XmlCursor cur = r.newCursor();
      try {
        cur.setAttributeText(QName.valueOf("{" + NS_W + "}rsidR"), rsid);
      } finally {
        cur.dispose();
      }
    } catch (RuntimeException e) {
      // RSID 设失败不阻断主创作流程(prd R5)
    }
  }
}
