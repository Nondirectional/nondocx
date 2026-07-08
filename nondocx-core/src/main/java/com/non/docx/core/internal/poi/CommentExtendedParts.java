package com.non.docx.core.internal.poi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>负责批注「线程 + 协作元数据」相关的<b>三个 part</b> 自维护——POI 5.2.5 对这三个 part <b>无 Java 类、无 API</b>,nondocx 用 OPC
 * 层从 part 级别白手起家。本类是 nondocx 首个「自维护 OOXML part」模式的实现。
 *
 * <p><b>三个 part(探针验证见 {@code research/part-lifecycle.md})。</b>
 *
 * <ul>
 *   <li><b>commentsExtended.xml</b>({@code w15} 命名空间)——线程关系。每条批注一个 {@code <w15:commentEx>}, 含
 *       {@code w15:paraId}(本批注 paraId)+ 可选 {@code w15:paraIdParent}(父批注 paraId,缺失=根批注)。
 *       <b>这是线程关系的唯一真源。</b>
 *   <li><b>commentsIds.xml</b>({@code w16cid})——durableId 映射。{@code <w16cid:commentId
 *       w16cid:paraId=.. w16cid:durableId=../>}。协作元数据,与线程结构无关。
 *   <li><b>commentsExtensible.xml</b>({@code w16cex})——w16cex 扩展。{@code <w16cex:commentExtensible
 *       w16cex:durableId=../>},可带 {@code w16du:dateUtc}。协作元数据。
 * </ul>
 *
 * <p><b>OPC part 生命周期(探针 §2)。</b>
 *
 * <ol>
 *   <li>{@link OPCPackage#createPart} 创建 part;<b>[Content_Types].xml 的 Override 由 POI
 *       自动注册</b>,无需手写。
 *   <li>{@link PackagePart#getOutputStream} 写内容;{@link PackagePart#getInputStream} 读回。
 *   <li>{@link PackagePart#addRelationship} 加 document.xml → part 的关系。
 *   <li><b>幂等坑</b>:重复 createPart(同名)抛 {@code PartAlreadyExistsException};本类先 {@link
 *       OPCPackage#getPart(PackagePartName)} 检查,存在追加、不存在才 create(探针 §2.3)。
 * </ol>
 *
 * <p><b>part 内容用 DOM 读-改-写。</b> 三个 part 文件小(几 KB),DOM 处理命名空间/转义比字符串拼接稳。
 * 解析失败时防御式返回空结果(视为「无线程信息」),不抛——保证畸形 part 不破坏读侧。
 */
final class CommentExtendedParts {

  // part 路径 / content type / relationship type(探针 §3 实测)
  private static final String EXTENDED_NAME = "/word/commentsExtended.xml";
  private static final String EXTENDED_CT =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml";
  private static final String EXTENDED_REL =
      "http://schemas.microsoft.com/office/2013/08/relationships/commentsExtended";

  private static final String IDS_NAME = "/word/commentsIds.xml";
  private static final String IDS_CT =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsIds+xml";
  private static final String IDS_REL =
      "http://schemas.microsoft.com/office/2013/08/relationships/commentsIds";

  private static final String EXTENSIBLE_NAME = "/word/commentsExtensible.xml";
  private static final String EXTENSIBLE_CT =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtensible+xml";
  private static final String EXTENSIBLE_REL =
      "http://schemas.microsoft.com/office/2013/08/relationships/commentsExtensible";

  // 命名空间 URI
  private static final String NS_W15 = "http://schemas.microsoft.com/office/word/2012/wordml";
  private static final String NS_W16CID =
      "http://schemas.microsoft.com/office/word/2016/wordml/cid";
  private static final String NS_W16CEX =
      "http://schemas.microsoft.com/office/word/2018/wordml/cex";

  private CommentExtendedParts() {}

  // ---------- id / 时间生成 ----------

  /**
   * 生成 8 位大写十六进制 id,范围 {@code [1, 0x7FFFFFFE]}(OOXML paraId/durableId 约束:必须 < 0x7FFFFFFF, 对照 docx
   * skill {@code _generate_hex_id})。
   */
  static String randomHexId() {
    // ThreadLocalRandom 省去 SecureRandom 开销;paraId/durableId 不要求密码学强度
    long v = 1L + java.util.concurrent.ThreadLocalRandom.current().nextLong(0x7FFFFFFE);
    return String.format("%08X", v);
  }

  /** 当前时间的 ISO-8601 UTC 字符串(如 {@code 2026-07-07T12:34:56Z}),用于 {@code w16du:dateUtc}。 */
  static String dateUtcNow() {
    return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
  }

  // ---------- 写:追加线程/元数据条目 ----------

  /**
   * 给三个 part 各追加一条条目(幂等:part 不存在则创建)。
   *
   * <p>调用方负责保证 {@code paraId}/{@code durableId}/{@code parentParaId} 已生成且 {@code parentParaId}
   * 指向真实存在的父批注 paraId(根批注传 {@code null})。
   *
   * @param document POI 文档(不能为 {@code null})
   * @param paraId 本批注的 paraId(不能为 {@code null})
   * @param parentParaId 父批注的 paraId;{@code null} = 根批注(无 paraIdParent)
   * @param durableId 本批注的 durableId(不能为 {@code null})
   * @param dateUtc ISO-8601 UTC 时间串(不能为 {@code null})
   */
  static void appendEntries(
      XWPFDocument document, String paraId, String parentParaId, String durableId, String dateUtc) {
    java.util.Objects.requireNonNull(document, "document");
    java.util.Objects.requireNonNull(paraId, "paraId");
    java.util.Objects.requireNonNull(durableId, "durableId");
    java.util.Objects.requireNonNull(dateUtc, "dateUtc");

    // commentsExtended: <w15:commentEx w15:paraId=.. [w15:paraIdParent=..] w15:done="0"/>
    appendEntry(
        document,
        EXTENDED_NAME,
        EXTENDED_CT,
        EXTENDED_REL,
        "w15",
        NS_W15,
        "commentsEx",
        "commentEx",
        attrs -> {
          attrs.putNS("w15", NS_W15);
          attrs.putAttr("w15", "paraId", paraId);
          if (parentParaId != null) {
            attrs.putAttr("w15", "paraIdParent", parentParaId);
          }
          attrs.putAttr("w15", "done", "0");
        });

    // commentsIds: <w16cid:commentId w16cid:paraId=.. w16cid:durableId=../>
    appendEntry(
        document,
        IDS_NAME,
        IDS_CT,
        IDS_REL,
        "w16cid",
        NS_W16CID,
        "commentsIds",
        "commentId",
        attrs -> {
          attrs.putNS("w16cid", NS_W16CID);
          attrs.putAttr("w16cid", "paraId", paraId);
          attrs.putAttr("w16cid", "durableId", durableId);
        });

    // commentsExtensible: <w16cex:commentExtensible w16cex:durableId=.. w16du:dateUtc=../>
    appendEntry(
        document,
        EXTENSIBLE_NAME,
        EXTENSIBLE_CT,
        EXTENSIBLE_REL,
        "w16cex",
        NS_W16CEX,
        "commentsExtensible",
        "commentExtensible",
        attrs -> {
          attrs.putNS("w16cex", NS_W16CEX);
          attrs.putAttr("w16cex", "durableId", durableId);
          attrs.putAttr(
              "w16du",
              "http://schemas.microsoft.com/office/word/2023/wordml/word16du",
              "dateUtc",
              dateUtc);
        });
  }

  /**
   * 给单个 part 追加一条条目:幂等 ensurePart → DOM 读-改-写。
   *
   * @param prefix 根元素与子元素的命名空间前缀(如 {@code w15})
   * @param rootLocal 根元素 local name(如 {@code commentsEx})
   * @param childLocal 子元素 local name(如 {@code commentEx})
   */
  private static void appendEntry(
      XWPFDocument document,
      String partName,
      String contentType,
      String relType,
      String prefix,
      String nsUri,
      String rootLocal,
      String childLocal,
      java.util.function.Consumer<AttrBuilder> buildChild) {
    try {
      PackagePart part =
          ensurePart(document, partName, contentType, relType, prefix, nsUri, rootLocal);
      Document dom = readOrCreateDom(part, prefix, nsUri, rootLocal);
      Element root = dom.getDocumentElement();
      AttrBuilder b = new AttrBuilder(dom);
      buildChild.accept(b);
      Element child = dom.createElementNS(nsUri, prefix + ":" + childLocal);
      for (String[] attr : b.attrs) { // [prefix, uri, local, value]
        child.setAttributeNS(attr[1], attr[0] + ":" + attr[2], attr[3]);
      }
      root.appendChild(child);
      writeDom(part, dom);
    } catch (Exception e) {
      // 单 part 追加失败不破坏其它 part:防御式吞掉,线程/元数据缺失不影响 comments.xml 正文(探针精神)
    }
  }

  /**
   * 幂等获取 part:存在返回,不存在则 createPart + addRelationship。 防御 {@code PartAlreadyExistsException}(探针
   * §2.3)。
   *
   * <p><b>package-private</b> 供同包的 {@link AuthoringInfra} 复用(people.xml 自维护同型)。createPart 自动注册
   * [Content_Types].xml 的 Override(N23);relationship 手动加。
   */
  static PackagePart ensurePart(
      XWPFDocument document,
      String partName,
      String contentType,
      String relType,
      String prefix,
      String nsUri,
      String rootLocal) {
    OPCPackage pkg = document.getPackage();
    try {
      PackagePartName name = PackagingURIHelper.createPartName(partName);
      PackagePart existing = pkg.getPart(name);
      if (existing != null) {
        return existing;
      }
      // 只 createPart + addRelationship,不预写空根——空根由 readOrCreateDom/writeDom 统一处理,
      // 避免「ensurePart 写空根 + writeDom 写完整」的双重写入导致 part 内容重复(实测会拼出两个 XML 声明)
      PackagePart part = pkg.createPart(name, contentType);
      document.getPackagePart().addRelationship(name, TargetMode.INTERNAL, relType);
      return part;
    } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
      throw new RuntimeException("part name " + partName + " 格式无效", e);
    }
  }

  // ---------- 读:解析线程关系 ----------

  /**
   * 解析 commentsExtended.xml,返回 {@code paraId → parentParaId} 映射。
   *
   * <p>根批注(无 paraIdParent)不出现在 Map 中(或值为 null)。part 不存在/解析失败时返回空 Map(防御式, 不抛)——畸形 part 不破坏读侧。
   */
  static Map<String, String> parseParents(XWPFDocument document) {
    Map<String, String> parents = new HashMap<>();
    PackagePart part = openPart(document, EXTENDED_NAME);
    if (part == null) {
      return parents;
    }
    try {
      Document dom = readDom(part);
      XPath xp = XPathFactory.newInstance().newXPath();
      NodeList nodes =
          (NodeList)
              xp.evaluate(
                  "//*[local-name()='commentEx']",
                  dom.getDocumentElement(),
                  XPathConstants.NODESET);
      for (int i = 0; i < nodes.getLength(); i++) {
        Element ex = (Element) nodes.item(i);
        String paraId = ex.getAttributeNS(NS_W15, "paraId");
        if (paraId.isEmpty()) {
          // 兼容非命名空间前缀的 DOM 读法(部分解析器 getAttributeNS 需要前缀已声明)
          paraId = ex.getAttribute("w15:paraId");
        }
        String parentParaId = ex.getAttributeNS(NS_W15, "paraIdParent");
        if (parentParaId.isEmpty()) {
          parentParaId = ex.getAttribute("w15:paraIdParent");
        }
        if (!paraId.isEmpty() && !parentParaId.isEmpty()) {
          parents.put(paraId, parentParaId);
        }
      }
    } catch (Exception e) {
      // 解析失败(XPath/DOM/IO 等):返回已收集的部分(空),不抛——畸形 part 不破坏读侧
    }
    return parents;
  }

  /** 获取 part(不存在返回 null)。 */
  private static PackagePart openPart(XWPFDocument document, String partName) {
    try {
      return document.getPackage().getPart(PackagingURIHelper.createPartName(partName));
    } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
      return null;
    }
  }

  // ---------- DOM 读写工具 ----------

  /** 读 part 为 DOM;package-private 供 {@link AuthoringInfra} 复用。 */
  static Document readDom(PackagePart part) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      DocumentBuilder b = f.newDocumentBuilder();
      try (java.io.InputStream in = part.getInputStream()) {
        return b.parse(in);
      }
    } catch (Exception e) {
      throw new RuntimeException("解析 part " + part.getPartName() + " 失败", e);
    }
  }

  /**
   * 读 part 为 DOM;part 空(刚 createPart 未写)或解析失败时建一个含空根元素的新 DOM。
   *
   * <p>写侧专用(appendEntry):ensurePart 不预写空根,故首次追加时 part 为空,本方法兜底建空根,供后续追加。 解析失败也建空根
   * (覆盖可能的畸形内容)而非抛——写侧不应被既有畸形内容阻塞。
   */
  /** package-private 供 {@link AuthoringInfra} 复用(people.xml 同型自维护)。 */
  static Document readOrCreateDom(PackagePart part, String prefix, String nsUri, String rootLocal) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      DocumentBuilder b = f.newDocumentBuilder();
      try (java.io.InputStream in = part.getInputStream()) {
        if (in.available() > 0) {
          return b.parse(in);
        }
      }
    } catch (Exception e) {
      // 解析失败:fall through 建空根(覆盖畸形内容)
    }
    // part 空/解析失败:建含空根元素的新 DOM
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      Document doc = f.newDocumentBuilder().newDocument();
      Element root = doc.createElementNS(nsUri, prefix + ":" + rootLocal);
      doc.appendChild(root);
      return doc;
    } catch (Exception e) {
      throw new RuntimeException("建空根 DOM 失败 for " + part.getPartName(), e);
    }
  }

  /** package-private 供 {@link AuthoringInfra} 复用。 */
  static void writeDom(PackagePart part, Document dom) {
    // POI 的 getOutputStream 在某些实现(MemoryPackagePart)下是「累加」而非「覆盖」语义——多次 writeDom 会让
    // part 内容拼出多份 XML 文档(实测见 research/part-lifecycle.md §7)。故写前先 clear,保证覆盖。
    if (part instanceof org.apache.poi.openxml4j.opc.internal.MemoryPackagePart) {
      ((org.apache.poi.openxml4j.opc.internal.MemoryPackagePart) part).clear();
    }
    try (java.io.OutputStream os = part.getOutputStream()) {
      TransformerFactory tf = TransformerFactory.newInstance();
      tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      Transformer t = tf.newTransformer();
      t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      t.setOutputProperty(OutputKeys.STANDALONE, "yes");
      t.transform(new DOMSource(dom), new StreamResult(os));
    } catch (Exception e) {
      throw new RuntimeException("写 part " + part.getPartName() + " 失败", e);
    }
  }

  /** 构造子元素属性的可变收集器(命名空间声明 + 带前缀属性)。package-private 供 {@link AuthoringInfra} 复用。 */
  static final class AttrBuilder {
    final Document doc;
    final Map<String, String> namespaceDecls = new HashMap<>();
    final List<String[]> attrs = new ArrayList<>(); // [prefix, uri, local, value]

    AttrBuilder(Document doc) {
      this.doc = doc;
    }

    void putNS(String prefix, String uri) {
      namespaceDecls.put(prefix, uri);
    }

    void putAttr(String prefix, String uri, String local, String value) {
      attrs.add(new String[] {prefix, uri, local, value});
    }

    /** 用已 putNS 注册的 uri 查找命名空间(常见场景:attr 与根同命名空间)。 */
    void putAttr(String prefix, String local, String value) {
      String uri = namespaceDecls.get(prefix);
      attrs.add(new String[] {prefix, uri == null ? "" : uri, local, value});
    }
  }
}
