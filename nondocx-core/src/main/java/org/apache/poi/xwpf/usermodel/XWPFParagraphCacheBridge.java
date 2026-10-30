package org.apache.poi.xwpf.usermodel;

import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.impl.values.XmlValueDisconnectedException;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSmartTagRun;

/**
 * 内部 API——恕不另行通知即可更改。
 *
 * <p>段落包装缓存桥接：XML 手术（{@code moveXml} / {@code removeXml}）后修复 POI {@link XWPFParagraph} 缓存的断连 run
 * 句柄。
 *
 * <p><b>为什么需要它。</b> POI 在文档打开时把 {@code XWPFParagraph.runs} / {@code iruns} 固化为 {@code XWPFRun}
 * 缓存（构造时 {@code buildRunsInOrderFromXml}），此后没有公开的重建入口。 当 XML 手术（tracked changes 的 move/del 创作
 * （{@code moveRuns} / {@code addDeletion}，含 {@code Run.replaceTracked} 复合路径）与 accept /
 * reject）把段落直接子 {@code <w:r>} 迁入 修订包装或删除节点时，缓存里的旧 {@code XWPFRun} 持有的 {@code CTR} 句柄即断连，此后任何经 POI
 * 缓存的读（{@code getText()} 对每个缓存 run 先调 {@code getDelTextArray()}）都抛 {@link
 * XmlValueDisconnectedException}。落盘走 XML 树序列化不经缓存，故 save+reopen 后一切正常—— 纯 live 会话缺陷。
 *
 * <p><b>同包桥接的合法性与限制。</b> 本类放在 {@code org.apache.poi.xwpf.usermodel} 包内，与 {@link XWPFParagraph}
 * 同包，从而<b>不经反射</b>直接访问其 {@code protected} 字段 {@code runs} / {@code iruns} / {@code part}；POI
 * 升级若改动字段名，本类在编译期即暴露。这是 nondocx「internal/poi 脏活」的指定位置，不属于对外公开 API（api/ 包签名零 POI 泄漏的约束不受影响）。
 *
 * <p><b>修复语义（按需重建，绝不引入新可见性）。</b> {@link #refreshIfDisconnected} 仅在缓存中 <b>确实存在断连句柄</b>时才清空并照 POI
 * open 语义重建——重建结果只把缓存修正回「与当前 XML 一致」：
 *
 * <ol>
 *   <li>纯新建创作路径（addInsertion / replaceTrackedRange 等）不产生断连，不触发重建，live {@code text()} 的既有语义（XML
 *       手术新建的 ins 不可见）原样保持。
 *   <li>move/del 创作后的源段：重建后 moveFrom/del 包装内 run（{@code delText}）入缓存， {@code getText()} 跳过
 *       delText——live 读值与 save+reopen 口径一致。
 *   <li>accept/reject（包装在文档打开时已存在、其内 run 已入缓存）：重建删除断连句柄，live 恢复可读， 读值与 reopen 口径一致。
 *   <li>accept（本会话新建包装）：unwrap 迁出的 run 不在缓存、无断连，不重建——「迁出的 run 在 live {@code text()} 不可见」的现状保持（§6.13
 *       已知形态）。
 * </ol>
 *
 * <p><b>已知边缘效应（显式接受）。</b> 若同一段落既有断连句柄、又有本会话 XML 手术新建的 {@code <w:ins>}，重建会把新建 ins 的 run 一并带入缓存（live
 * 可见性向 reopen 对齐）。方向正确（更接近 落盘真相）、无现有断言依赖旧态，不做特判排除（XML 上没有「本会话新建」的身份信息）。
 *
 * <p><b>footnoteText 不重建。</b> POI 在段落构造时把脚注引用文本快照进私有 {@code footnoteText}，
 * 本类不动它——「脚注引用与修订手术落在同段」属极端边界，维持构造期快照（重建后 {@code getText()} 仍会拼上这段快照，与 POI open 语义一致）。
 *
 * <p><b>POI 升级复核要求。</b> 重建遍历<b>逐分支照抄 POI 5.2.5 {@code XWPFParagraph
 * .buildRunsInOrderFromXml}</b>（直接子 CTR / CTHyperlink 内 run / CTSimpleField / CTSdtBlock 与 CTSdtRun
 * 仅入 iruns / CTRunTrackChange 含嵌套 ins 递归 / CTSmartTagRun 递归）。升级 POI 版本时
 * <b>必须</b>解包新版源码对照该方法复核本类，分支清单或包装类型有任何出入都要同步修订。
 */
public final class XWPFParagraphCacheBridge {

  private XWPFParagraphCacheBridge() {}

  /**
   * 检测段落缓存中是否存在已断连的 run 句柄。
   *
   * <p>对每个已缓存 {@link XWPFRun} 调一次 {@code getCTR().getDelTextArray()}（与 POI {@code getText()}
   * 的崩溃点同一路径），捕获 {@link XmlValueDisconnectedException} 即判定断连。 只读检测，不修改任何状态。
   *
   * @param paragraph 要检测的段落（不能为 {@code null}）
   * @return {@code true} 表示缓存中存在断连句柄，需要 {@link #refreshIfDisconnected} 修复
   */
  public static boolean hasDisconnectedRuns(XWPFParagraph paragraph) {
    for (XWPFRun run : paragraph.runs) {
      try {
        run.getCTR().getDelTextArray();
      } catch (XmlValueDisconnectedException e) {
        return true;
      }
    }
    return false;
  }

  /**
   * 若缓存中存在断连句柄则重建段落缓存；无断连则完全不动。
   *
   * <p>重建 = 清空 {@code runs} / {@code iruns}，照 POI open 语义（{@code buildRunsInOrderFromXml}
   * 同一分支清单）从当前 {@code CTP} 重新遍历构造。段落对象身份不变（{@code getCTP()} 委托仍在树上）， 容器（XWPFDocument.paragraphs
   * 等）零触达，落盘 XML 零变化。
   *
   * @param paragraph 要修复的段落（不能为 {@code null}）
   */
  public static void refreshIfDisconnected(XWPFParagraph paragraph) {
    if (!hasDisconnectedRuns(paragraph)) {
      return;
    }
    paragraph.runs.clear();
    paragraph.iruns.clear();
    rebuildRunsInOrder(paragraph, paragraph.getCTP());
  }

  /**
   * 照抄 POI 5.2.5 {@code XWPFParagraph.buildRunsInOrderFromXml} 的重建遍历。
   *
   * <p>分支清单（勿自创，升级 POI 时对照新版复核，见类 Javadoc）：直接子 {@code CTR} → {@link XWPFRun}；{@code CTHyperlink} 内
   * run → {@link XWPFHyperlinkRun}；{@code CTSimpleField} → {@link XWPFFieldRun}；{@code CTSdtBlock}
   * / {@code CTSdtRun} → {@link XWPFSDT}（仅入 iruns）；{@code CTRunTrackChange}（ins/del/moveFrom/moveTo
   * 包装）内 run → {@link XWPFRun}； {@code CTSmartTagRun} 递归；{@code CTRunTrackChange} 的嵌套 {@code
   * getInsArray()} 递归。 {@code runs} 与 {@code iruns} 同步重建。
   */
  @SuppressWarnings("deprecation")
  private static void rebuildRunsInOrder(XWPFParagraph paragraph, XmlObject object) {
    XmlCursor c = object.newCursor();
    try {
      c.selectPath("child::*");
      while (c.toNextSelection()) {
        XmlObject o = c.getObject();
        if (o instanceof CTR) {
          XWPFRun r = new XWPFRun((CTR) o, paragraph);
          paragraph.runs.add(r);
          paragraph.iruns.add(r);
        }
        if (o instanceof CTHyperlink) {
          CTHyperlink link = (CTHyperlink) o;
          for (CTR r : link.getRArray()) {
            XWPFHyperlinkRun hr = new XWPFHyperlinkRun(link, r, paragraph);
            paragraph.runs.add(hr);
            paragraph.iruns.add(hr);
          }
        }
        if (o instanceof CTSimpleField) {
          CTSimpleField field = (CTSimpleField) o;
          for (CTR r : field.getRArray()) {
            XWPFFieldRun fr = new XWPFFieldRun(field, r, paragraph);
            paragraph.runs.add(fr);
            paragraph.iruns.add(fr);
          }
        }
        if (o instanceof CTSdtBlock) {
          XWPFSDT cc = new XWPFSDT((CTSdtBlock) o, paragraph.part);
          paragraph.iruns.add(cc);
        }
        if (o instanceof CTSdtRun) {
          XWPFSDT cc = new XWPFSDT((CTSdtRun) o, paragraph.part);
          paragraph.iruns.add(cc);
        }
        if (o instanceof CTRunTrackChange) {
          for (CTR r : ((CTRunTrackChange) o).getRArray()) {
            XWPFRun cr = new XWPFRun(r, paragraph);
            paragraph.runs.add(cr);
            paragraph.iruns.add(cr);
          }
        }
        if (o instanceof CTSmartTagRun) {
          // SmartTag 可多层嵌套，POI 同样递归（不保留 tagging 信息）
          rebuildRunsInOrder(paragraph, o);
        }
        if (o instanceof CTRunTrackChange) {
          // 修订包装内嵌套的 ins 也按文本展开（POI 同款递归）
          for (CTRunTrackChange change : ((CTRunTrackChange) o).getInsArray()) {
            rebuildRunsInOrder(paragraph, change);
          }
        }
      }
    } finally {
      c.dispose();
    }
  }
}
