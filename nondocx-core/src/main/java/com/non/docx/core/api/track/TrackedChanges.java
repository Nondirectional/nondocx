package com.non.docx.core.api.track;

import com.non.docx.core.internal.poi.TrackedChangeNodes;
import com.non.docx.core.internal.util.Objects;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * 文档的修订(tracked changes)能力门面 —— 一个只读视图,持有 {@link XWPFDocument} 委托。
 *
 * <p>由 {@link com.non.docx.core.api.Document#trackedChanges()} 返回。本门面负责三件<b>只读</b>事:
 *
 * <ul>
 *   <li>{@link #enabled()} —— 读取文档是否开启修订记录({@code settings.xml} 的 {@code <w:trackChanges/>})。
 *   <li>{@link #list()} —— 按文档顺序枚举全部修订(第一版稳定覆盖文本类,高级类型由 {@code advanced-types} 子任务补齐)。
 *   <li>{@link #get(String)} —— 按 {@link TrackedChange#id() 稳定 id} 获取单条修订(进程内稳定)。
 * </ul>
 *
 * <p><b>OOXML / POI / nondocx 三层。</b>
 *
 * <ul>
 *   <li><b>OOXML</b>:开关在 {@code word/settings.xml} 的 {@code <w:trackChanges/>}(有即开启);修订标记散落在 {@code
 *       word/document.xml} 正文各处,如 {@code <w:ins>} / {@code <w:del>}。
 *   <li><b>POI</b>:没有 {@code XWPFTrackedChanges} 高级 API,也没有遍历修订的现成方法。开关需通过 {@code CTSettings}
 *       读取;修订节点需用 {@code XmlCursor} 按文档顺序遍历 body 树。
 *   <li><b>nondocx</b>:把这两件脏活收进 {@code internal/poi/TrackedChangeNodes},对外只暴露本类与 {@link
 *       TrackedChange} 等干净类型。
 * </ul>
 *
 * <p><b>只读。</b> 本门面只负责诚实暴露修订状态与列表,不修改 {@code settings.xml}、不修改 {@code document.xml}。
 * 开关写入、accept/reject 均不属于本子任务。
 *
 * <p><b>活对象语义(无字段快照)。</b> 本门面持有单个 {@code final XWPFDocument} 委托;{@code list()} 与 {@code get(id)}
 * 每次调用都<b>当场重算</b>,不缓存修订列表——因此文档改动会实时反映,守住「无字段快照」精神(与 {@code TableOfContents.entries()} 一致)。{@code
 * get(id)} 内部为反查 id 会临时构建一张「id → 修订」映射,但那是方法内的 临时状态,不跨调用存活,因此不违反 holding-wrapper 约定。
 *
 * <p><b>稳定 id 的进程内稳定性(design §4.5)。</b> {@link #list()} 与 {@link #get(String)} 各自独立重算;同一修订在
 * <b>同一次</b>调用内 id 稳定、可在该调用内被 {@code get} 反查。{@code list()} 两次调用的 id 也保持一致(同一委托、 同一文档顺序、同一 id
 * 生成规则)。但<b>不承诺</b> {@code save()} 后重新 {@code Docx.open()} 仍稳定。
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
