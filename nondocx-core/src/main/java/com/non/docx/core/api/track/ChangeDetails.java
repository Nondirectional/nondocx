package com.non.docx.core.api.track;

/**
 * 修订的<b>具体 payload 语义</b>——回答「这条修订改了什么」。
 *
 * <p>每个 {@link TrackedChange} 通过 {@link TrackedChange#details()} 返回一个本接口的实现。不同的修订 family 对应不同的
 * details 子类型,携带各自关心的 payload(例如文本类修订携带被插入/删除的文本)。
 *
 * <p><b>location 与 details 的分工(design §4.4)。</b>
 *
 * <ul>
 *   <li>{@link TrackedChangeLocation location} 回答「这个修订挂在文档哪里」——纯结构位置。
 *   <li>{@code details()} 回答「这个修订改了什么」——具体 payload 语义。
 * </ul>
 *
 * 对于属性类修订,location 只表达结构位置;具体属性目标(如 {@code rPr} / {@code pPr} / {@code sectPr})由 {@code
 * PropertyChangeDetails} 表达,不挤进 location。
 *
 * <p><b>当前 details 族。</b> read 子任务稳定支持 {@link TextChangeDetails}(文本类插入/删除)。其余子类型 ({@code
 * MoveChangeDetails} / {@code PropertyChangeDetails} / {@code CellChangeDetails})的完整建模留给 {@code
 * advanced-types} 子任务,届时在同一接口上扩子类型即可,不影响本接口契约。
 *
 * <p><b>这是一个判别式联合(discriminated union)的根接口。</b> 调用方通常用 {@code instanceof} 判断具体子类型, 再读取其专属字段。details
 * 自身是 POI-free 的不可变值。
 *
 * @see TextChangeDetails
 */
public interface ChangeDetails {}
