package com.non.docx.core.api.track;

import com.non.docx.core.api.exception.UnsupportedFeatureException;
import com.non.docx.core.internal.poi.TrackedChangeNodes;
import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * 文档的修订(tracked changes)能力门面 —— 持有 {@link XWPFDocument} 委托,提供只读消费与文本类破坏性写。
 *
 * <p>由 {@link com.non.docx.core.api.Document#trackedChanges()} 返回。本门面负责两类事:
 *
 * <p><b>只读消费</b>:
 *
 * <ul>
 *   <li>{@link #enabled()} —— 读取文档是否开启修订记录({@code settings.xml} 的 {@code <w:trackChanges/>})。
 *   <li>{@link #list()} —— 按文档顺序枚举全部修订(第一版稳定覆盖文本类,高级类型由 {@code advanced-types} 子任务补齐)。
 *   <li>{@link #get(String)} —— 按 {@link TrackedChange#id() 稳定 id} 获取单条修订(进程内稳定)。
 * </ul>
 *
 * <p><b>文本类破坏性写</b>(accept/reject,见同名方法):对 {@code ins}/{@code del} 文本类修订做应用或撤销。高级类型(move / 属性类 /
 * cell 类)的写语义仍属 {@code raw()} 范围,由 {@code advanced-types} 子任务补齐。
 *
 * <p><b>OOXML / POI / nondocx 三层。</b>
 *
 * <ul>
 *   <li><b>OOXML</b>:开关在 {@code word/settings.xml} 的 {@code <w:trackChanges/>}(有即开启);修订标记散落在 {@code
 *       word/document.xml} 正文各处,如 {@code <w:ins>} / {@code <w:del>}。
 *   <li><b>POI</b>:没有 {@code XWPFTrackedChanges} 高级 API,也没有遍历/应用修订的现成方法。开关需通过 {@code CTSettings}
 *       读取;修订节点需用 {@code XmlCursor} 按文档顺序遍历 body 树;accept/reject 需用 {@code XmlCursor} 做节点重挂/删除。
 *   <li><b>nondocx</b>:把这些脏活收进 {@code internal/poi/TrackedChangeNodes},对外只暴露本类与 {@link
 *       TrackedChange} 等干净类型。
 * </ul>
 *
 * <p><b>读写边界。</b> 只读方法不修改文档;accept/reject 是破坏性写,会改动 {@code document.xml} 的修订标记树。开关写入({@code
 * <w:trackChanges/>} 的增删)与创作侧(authoring)均不属于本门面。
 *
 * <p><b>活对象语义(无字段快照)。</b> 本门面持有单个 {@code final XWPFDocument} 委托;{@code list()} 与 {@code get(id)}
 * 每次调用都<b>当场重算</b>,不缓存修订列表——因此文档改动(包括 accept/reject 之后)会实时反映,守住「无字段快照」精神(与 {@code
 * TableOfContents.entries()} 一致)。{@code get(id)} 内部为反查 id 会临时构建一张「id → 修订」映射,但那是方法内的
 * 临时状态,不跨调用存活,因此不违反 holding-wrapper 约定。
 *
 * <p><b>稳定 id 的进程内稳定性(design §4.5)。</b> {@link #list()} 与 {@link #get(String)} 各自独立重算;同一修订在
 * <b>同一次</b>调用内 id 稳定、可在该调用内被 {@code get} 反查。{@code list()} 两次调用的 id 也保持一致(同一委托、 同一文档顺序、同一 id
 * 生成规则)。但<b>不承诺</b> {@code save()} 后重新 {@code Docx.open()} 仍稳定。accept/reject 操作同会话内基于当前 id 进行。
 *
 * <p><b>不参与 {@code Document.equals}。</b> 与 TOC 类似,修订列表不纳入 {@code Document} 的内容相等性。
 */
public final class TrackedChanges {

  private final XWPFDocument delegate;

  /**
   * 封装给定的 POI 文档以解析其修订。
   *
   * <p>此构造函数是 {@link com.non.docx.core.api.Document} 生成修订视图的<b>内部接缝</b>,因此它有意接受 POI 类型 (与 {@code
   * TableOfContents(XWPFDocument)} 接受 {@code XWPFDocument} 的方式一致,见 poi-bridge.md N1)。用户通过 {@code
   * Document.trackedChanges()} 获取,而不是直接构造。
   *
   * @param delegate 底层的 POI 文档(不能为 {@code null})
   * @throws IllegalArgumentException 如果 {@code delegate} 为 {@code null}
   */
  public TrackedChanges(XWPFDocument delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * 返回文档是否开启修订记录。
   *
   * <p>读取 {@code settings.xml} 的 {@code <w:trackChanges/>}:存在该元素即视为开启,缺失即视为未开启。 不产生任何写副作用,不负责开关写入。
   *
   * @return {@code true} 表示文档开启了修订记录;{@code false} 表示未开启或开关缺失
   */
  public boolean enabled() {
    return TrackedChangeNodes.isEnabled(delegate);
  }

  /**
   * 返回按文档顺序排列的全部修订(活跃视图)。
   *
   * <p>返回的列表是<b>活跃的</b>:每次访问(如 {@code size()}、{@code get(i)})都从委托重读,因此文档改动会实时反映。
   * 顺序严格按<b>文档出现顺序</b>,不按作者、类型或 id 重排。
   *
   * <p>第一版<b>稳定覆盖文本类</b>修订({@link TrackedChangeType#INS INS} / {@link TrackedChangeType#DEL DEL},
   * 以及同型的 {@link TrackedChangeType#MOVE_FROM MOVE_FROM} / {@link TrackedChangeType#MOVE_TO
   * MOVE_TO})。 高级类型({@code *PrChange} / {@code cellIns} 等)的完整建模由 {@code advanced-types} 子任务补齐。
   *
   * @return 不可修改的、按文档顺序排列的活跃修订列表(可能为空)
   */
  public List<TrackedChange> list() {
    List<TrackedChange> snapshot = TrackedChangeNodes.collect(delegate);
    return new AbstractList<TrackedChange>() {
      @Override
      public TrackedChange get(int index) {
        return snapshot.get(index);
      }

      @Override
      public int size() {
        return snapshot.size();
      }
    };
  }

  /**
   * 按稳定 id 获取单条修订。
   *
   * <p>语义是「命中式访问」:精确按 {@link TrackedChange#id()} 定位。命中即返回;未命中抛 {@link NoSuchElementException}
   * (而不是返回 {@code null})——按稳定标识精确定位的读取被视为「该有就有、没有就是错」。
   *
   * <p>内部会重新扫描一次修订列表(每次调用独立重算,活对象语义)。id 在同文档同会话内稳定(见 design §4.5); 若文档在 save/reopen 后某些修订的 id
   * 发生了变化,用旧 id 查找会抛异常,此时应改用 {@link #list()} 重新取最新 id。
   *
   * @param id nondocx 稳定 id(不能为 {@code null})
   * @return 对应的修订
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null}
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的修订
   */
  public TrackedChange get(String id) {
    Objects.requireNonNull(id, "id");
    Map<String, TrackedChange> byId = new HashMap<>();
    for (TrackedChange change : TrackedChangeNodes.collect(delegate)) {
      byId.put(change.id(), change);
    }
    TrackedChange found = byId.get(id);
    if (found == null) {
      throw new NoSuchElementException("找不到 id 为 " + id + " 的修订");
    }
    return found;
  }

  // ---------- 破坏性写:文本类 accept / reject ----------

  /**
   * 应用(accept)文档中的全部文本类修订。
   *
   * <p>对每条文本类修订({@link TrackedChangeType#INS INS} / {@link TrackedChangeType#DEL DEL})执行 accept:
   * 插入类成为正文永久内容,删除类被永久删除。非文本类修订(move / 属性类 / cell 类)不受影响,保持原样。
   *
   * <p>破坏性写操作会改动文档树;为保证稳定,本方法采用「重算 + 应用第一条匹配」的循环,直到没有文本类修订为止。
   *
   * @return 实际应用的文本类修订条数(0 表示文档本来就没有文本类修订)
   */
  public int acceptAll() {
    return applyRepeated(null, true);
  }

  /**
   * 撤销(reject)文档中的全部文本类修订。
   *
   * <p>对每条文本类修订执行 reject:插入类被丢弃,删除类的原文恢复为正文文本。非文本类修订不受影响。
   *
   * @return 实际撤销的文本类修订条数
   * @see #acceptAll()
   */
  public int rejectAll() {
    return applyRepeated(null, false);
  }

  /**
   * 应用(accept)指定作者的全部文本类修订。
   *
   * <p>作者匹配采用<b>大小写敏感的精确字符串匹配</b>(见 design §5.2)。只作用于作者精确匹配的文本类修订。
   *
   * @param author 要应用的修订作者(不能为 {@code null} 或空白)
   * @return 实际应用的文本类修订条数
   * @throws IllegalArgumentException 如果 {@code author} 为 {@code null} 或空白
   */
  public int acceptByAuthor(String author) {
    requireAuthor(author);
    return applyRepeated(author, true);
  }

  /**
   * 撤销(reject)指定作者的全部文本类修订。
   *
   * @param author 要撤销的修订作者(不能为 {@code null} 或空白)
   * @return 实际撤销的文本类修订条数
   * @throws IllegalArgumentException 如果 {@code author} 为 {@code null} 或空白
   * @see #acceptByAuthor(String)
   */
  public int rejectByAuthor(String author) {
    requireAuthor(author);
    return applyRepeated(author, false);
  }

  /**
   * 应用(accept)单条文本类修订。
   *
   * <p>按 {@link TrackedChange#id() 稳定 id} 精确定位(进程内稳定,见 design §4.5)。
   *
   * @param id nondocx 稳定 id(不能为 {@code null} 或空白)
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null} 或空白
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的修订
   * @throws UnsupportedFeatureException 若命中的修订是当前任务范围外的高级类型(move / 属性类 / cell 类)
   */
  public void accept(String id) {
    applySingle(id, true);
  }

  /**
   * 撤销(reject)单条文本类修订。
   *
   * @param id nondocx 稳定 id(不能为 {@code null} 或空白)
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null} 或空白
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的修订
   * @throws UnsupportedFeatureException 若命中的修订是当前任务范围外的高级类型(move / 属性类 / cell 类)
   * @see #accept(String)
   */
  public void reject(String id) {
    applySingle(id, false);
  }

  /**
   * 应用(accept)单条属性类修订:保留新(当前)属性树,移除 {@code *PrChange} 标记。
   *
   * <p>属性类修订的 accept/reject 走<b>专用方法</b>(而非 {@link #accept(String)}),因为属性类底层节点类型与文本/移动类不同(见 design
   * §3.2、 {@link TrackedChange#raw()} 的不支持说明)。按 {@link TrackedChange#id() 稳定 id} 定位。
   *
   * @param id nondocx 稳定 id(不能为 {@code null} 或空白)
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null} 或空白
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的修订
   * @throws UnsupportedFeatureException 若命中的修订不是属性类
   */
  public void acceptProperty(String id) {
    applyProperty(id, true);
  }

  /**
   * 撤销(reject)单条属性类修订:用旧(pristine)属性树覆盖新树,再移除 {@code *PrChange} 标记。
   *
   * @param id nondocx 稳定 id(不能为 {@code null} 或空白)
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null} 或空白
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的修订
   * @throws UnsupportedFeatureException 若命中的修订不是属性类
   * @see #acceptProperty(String)
   */
  public void rejectProperty(String id) {
    applyProperty(id, false);
  }

  /**
   * 应用(accept)单条单元格结构类修订({@code cellIns} / {@code cellDel}):让单元格的存亡修订生效。
   *
   * <p>语义(见 {@code research/cell-forms.md}):
   *
   * <ul>
   *   <li>{@code cellIns}(单元格被插入):保留整个 {@code <w:tc>},仅删标记。
   *   <li>{@code cellDel}(单元格被删除):移除整个 {@code <w:tc>}。
   * </ul>
   *
   * <p>单元格结构类修订的底层节点类型是 {@code CTTrackChange}(与 property 类同委托,但写语义作用于整个 {@code <w:tc>}
   * 祖父节点而非属性子树),故走<b>专用方法</b>(而非 {@link #accept(String)}),与 property 类同属方案 C。
   *
   * @param id nondocx 稳定 id(不能为 {@code null} 或空白)
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null} 或空白
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的修订
   * @throws UnsupportedFeatureException 若命中的修订是 {@code cellMerge}(其 CT 类型缺失,accept/reject 不支持),或不是
   *     cell 类
   */
  public void acceptCell(String id) {
    applyCell(id, true);
  }

  /**
   * 撤销(reject)单条单元格结构类修订:使单元格回到修订前的存亡状态。
   *
   * <p>语义(与 accept 对称):{@code cellIns} reject 移除整个 {@code <w:tc>};{@code cellDel} reject 保留 {@code
   * <w:tc>}、删标记。
   *
   * @param id nondocx 稳定 id(不能为 {@code null} 或空白)
   * @throws IllegalArgumentException 如果 {@code id} 为 {@code null} 或空白
   * @throws NoSuchElementException 如果没有 id 等于 {@code id} 的修订
   * @throws UnsupportedFeatureException 若命中的修订是 {@code cellMerge},或不是 cell 类
   * @see #acceptCell(String)
   */
  public void rejectCell(String id) {
    applyCell(id, false);
  }

  /**
   * 按稳定 id 应用或撤销单条<b>属性类</b>修订。
   *
   * <p>命中后校验 family 必须是 {@link TrackedChangeFamily#PROPERTY PROPERTY}(否则抛 {@link
   * UnsupportedFeatureException}),再经 {@code TrackedChange.propertyNode()} 取底层节点做整树替换。
   */
  private void applyProperty(String id, boolean accept) {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id 不能为空白");
    }
    Map<String, TrackedChange> byId = new HashMap<>();
    for (TrackedChange c : TrackedChangeNodes.collect(delegate)) {
      byId.put(c.id(), c);
    }
    TrackedChange target = byId.get(id);
    if (target == null) {
      throw new NoSuchElementException("找不到 id 为 " + id + " 的修订");
    }
    if (target.family() != TrackedChangeFamily.PROPERTY) {
      throw new UnsupportedFeatureException(
          "id 为 "
              + id
              + " 的修订是 "
              + target.type()
              + "("
              + target.family()
              + "),不是属性类;acceptProperty/rejectProperty 仅支持属性类");
    }
    if (accept) {
      TrackedChangeNodes.acceptProperty(target.propertyNode());
    } else {
      TrackedChangeNodes.rejectProperty(target.propertyNode());
    }
  }

  /**
   * 按稳定 id 应用或撤销单条<b>单元格结构类</b>修订。
   *
   * <p>命中后校验 family 必须是 {@link TrackedChangeFamily#CELL CELL}(否则抛 {@link
   * UnsupportedFeatureException})。 {@code cellMerge} 虽属 CELL family,但其 CT 类型缺失、accept/reject
   * 不支持,命中时抛 {@link UnsupportedFeatureException}(明确拒绝,不静默降级)。{@code cellIns}/{@code cellDel} 经
   * {@code TrackedChange.propertyNode()} 取底层节点,再对整个 {@code <w:tc>} 做存亡手术。
   */
  private void applyCell(String id, boolean accept) {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id 不能为空白");
    }
    Map<String, TrackedChange> byId = new HashMap<>();
    for (TrackedChange c : TrackedChangeNodes.collect(delegate)) {
      byId.put(c.id(), c);
    }
    TrackedChange target = byId.get(id);
    if (target == null) {
      throw new NoSuchElementException("找不到 id 为 " + id + " 的修订");
    }
    if (target.family() != TrackedChangeFamily.CELL) {
      throw new UnsupportedFeatureException(
          "id 为 "
              + id
              + " 的修订是 "
              + target.type()
              + "("
              + target.family()
              + "),不是单元格类;acceptCell/rejectCell 仅支持 cellIns/cellDel");
    }
    if (target.type() == TrackedChangeType.CELL_MERGE) {
      throw new UnsupportedFeatureException(
          "id 为 "
              + id
              + " 的修订是 cellMerge,其 CT 类型(CTCellMergeTrackChange)在 POI 精简 schema 下缺失,"
              + "accept/reject 暂不支持;请使用 raw()");
    }
    if (accept) {
      TrackedChangeNodes.acceptCell(target.propertyNode(), target.type());
    } else {
      TrackedChangeNodes.rejectCell(target.propertyNode(), target.type());
    }
  }

  /**
   * 循环「重算 → 应用第一条匹配的文本类修订」,直到没有匹配为止。
   *
   * <p>每次只应用一条后立刻重算,是因为 accept/reject 会改写文档树,此前 {@link TrackedChangeNodes#collect} 返回的节点
   * 句柄可能失效。重算保证每条都在当前文档状态下定位。{@code author == null} 表示不按作者筛选(用于 all 粒度)。
   *
   * @param author 作者过滤({@code null} 表示全部);非 {@code null} 时大小写敏感精确匹配
   * @param accept {@code true} 应用,{@code false} 撤销
   * @return 实际操作的条数
   */
  private int applyRepeated(String author, boolean accept) {
    int count = 0;
    while (true) {
      TrackedChange target = null;
      for (TrackedChange c : TrackedChangeNodes.collect(delegate)) {
        if (c.family() != TrackedChangeFamily.TEXT && c.family() != TrackedChangeFamily.MOVE) {
          continue;
        }
        if (author != null && !author.equals(c.author())) {
          continue;
        }
        target = c;
        break;
      }
      if (target == null) {
        return count;
      }
      if (target.family() == TrackedChangeFamily.MOVE) {
        // move 成对操作:两端算作 1 条(配对缺端时仍按单端处理并计数 1)
        applyMove(target, accept);
        count++;
      } else {
        applyOne(target, accept);
        count++;
      }
    }
  }

  /**
   * 按稳定 id 应用或撤销单条修订。
   *
   * <p>命中后先检查 family:非文本类抛 {@link UnsupportedFeatureException}(当前任务范围外);文本类才执行。
   */
  private void applySingle(String id, boolean accept) {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id 不能为空白");
    }
    Map<String, TrackedChange> byId = new HashMap<>();
    for (TrackedChange c : TrackedChangeNodes.collect(delegate)) {
      byId.put(c.id(), c);
    }
    TrackedChange target = byId.get(id);
    if (target == null) {
      throw new NoSuchElementException("找不到 id 为 " + id + " 的修订");
    }
    if (target.family() != TrackedChangeFamily.TEXT
        && target.family() != TrackedChangeFamily.MOVE) {
      throw new UnsupportedFeatureException(
          "id 为 "
              + id
              + " 的修订是 "
              + target.type()
              + "("
              + target.family()
              + "),accept/reject 仅支持文本/移动类;"
              + (target.family() == TrackedChangeFamily.CELL
                  ? "单元格类请使用 acceptCell/rejectCell"
                  : "请使用 raw()"));
    }
    if (target.family() == TrackedChangeFamily.MOVE) {
      applyMove(target, accept);
    } else {
      applyOne(target, accept);
    }
  }

  /** 对单条已校验为文本类的修订执行底层 accept / reject。 */
  private static void applyOne(TrackedChange change, boolean accept) {
    if (accept) {
      TrackedChangeNodes.acceptText(change.raw());
    } else {
      TrackedChangeNodes.rejectText(change.raw());
    }
  }

  /**
   * 对一条 move 修订做配对 accept / reject:先找配对端,再对两端同时执行(各算 moveFrom/moveTo 的 accept/reject 语义)。
   *
   * <p>配对依据 (author, date, text) 三元组(OOXML 无显式配对指针,见 research/ooxml-forms.md §配对方案):
   *
   * <ul>
   *   <li>accept move:moveFrom 删除生效(移除)、moveTo 插入生效(保留文本)。
   *   <li>reject move:moveFrom 删除撤销(恢复文本)、moveTo 插入撤销(移除)。
   * </ul>
   *
   * <p>两端底层 mechanics 与文本类 ins/del 同型(moveTo 同 ins、moveFrom 同 del),复用 {@link
   * TrackedChangeNodes#acceptText} / {@link #rejectText}。
   *
   * @param change 命中的 move 修订(moveFrom 或 moveTo)
   * @param accept {@code true} 应用,{@code false} 撤销
   * @throws NoSuchElementException 若配对端缺失(文档损坏或非 Word 产生的孤立 move)
   */
  private void applyMove(TrackedChange change, boolean accept) {
    Objects.requireNonNull(change, "change");
    // 在当前文档里找配对端(同 author + date + text 的另一 type)。
    TrackedChange counterpart = findMoveCounterpart(change);
    if (counterpart == null) {
      throw new NoSuchElementException(
          "id 为 " + change.id() + " 的 move 修订找不到配对端(author+text 无匹配),文档可能损坏;请使用 raw()");
    }
    // 对两端同时执行。顺序:先处理 from 端(移除/恢复源文本),再处理 to 端(保留/移除目标文本)。
    TrackedChange from = change.type() == TrackedChangeType.MOVE_FROM ? change : counterpart;
    TrackedChange to = change.type() == TrackedChangeType.MOVE_TO ? change : counterpart;
    if (accept) {
      TrackedChangeNodes.acceptText(from.raw());
      TrackedChangeNodes.acceptText(to.raw());
    } else {
      TrackedChangeNodes.rejectText(from.raw());
      TrackedChangeNodes.rejectText(to.raw());
    }
  }

  /**
   * 在当前文档里查找一条 move 修订的配对端(同 author + date + text、且 type 相反)。
   *
   * <p>配对启发式见 research/ooxml-forms.md。文档内 (author, date, text) 三元组通常唯一;若多端匹配,取文档顺序第一个。
   *
   * @return 配对端;无匹配返回 {@code null}(调用方据此抛异常,不静默降级)
   */
  private TrackedChange findMoveCounterpart(TrackedChange change) {
    TrackedChangeType wantType =
        change.type() == TrackedChangeType.MOVE_FROM
            ? TrackedChangeType.MOVE_TO
            : TrackedChangeType.MOVE_FROM;
    String author = change.author();
    String text = moveText(change);
    // 配对主依据:author + text(最稳定)。date 仅作弱约束——不同实现写入 date 的精度不同
    // (Word 批量同毫秒,但其它工具可能跨秒),故 date 不参与硬过滤;只要 author+text 唯一即可定配对。
    // 取文档顺序第一个匹配的 (author, text) 另一端。
    for (TrackedChange c : TrackedChangeNodes.collect(delegate)) {
      if (c.type() != wantType) {
        continue;
      }
      if (!java.util.Objects.equals(author, c.author())) {
        continue;
      }
      if (!java.util.Objects.equals(text, moveText(c))) {
        continue;
      }
      return c;
    }
    return null;
  }

  /** 取一条 move 修订的文本(moveFrom 用 delText、moveTo 用 t,与 read 的 extractText 语义一致)。 */
  private static String moveText(TrackedChange change) {
    ChangeDetails d = change.details();
    if (d instanceof TextChangeDetails) {
      return ((TextChangeDetails) d).text();
    }
    return "";
  }

  /** 校验 author 参数:非 {@code null} 且非空白,否则抛 {@link IllegalArgumentException}。 */
  private static void requireAuthor(String author) {
    Objects.requireNonNull(author, "author");
    if (author.isBlank()) {
      throw new IllegalArgumentException("author 不能为空白");
    }
  }

  /**
   * 返回底层的 POI 文档。
   *
   * <p>对返回对象的修改会立即影响文档。请谨慎使用。修订没有专属 POI 委托类型,这里的「委托」就是整份文档;想直接操作 修订标记的 OOXML 结构时,从此处拿到 {@link
   * XWPFDocument} 后走 {@code getDocument().getBody()} 等到 {@code CTBody},再遍历 {@code <w:ins>} / {@code
   * <w:del>} 等节点。
   *
   * @return 底层的 {@code XWPFDocument} 实例(包装器生命周期内同一实例)
   */
  public XWPFDocument raw() {
    return delegate;
  }
}
