package io.github.nondirectional.docx.core.api;

/**
 * 出现在段落内部的内联片段，按阅读顺序排列。
 *
 * <p>段落的内容是一个有序的内联元素序列——主要是运行（run）、超链接和内联图片。 {@code Paragraph.inlineElements()}
 * 返回它们在真实阅读顺序中的排列，此接口是该序列的共享类型。 它是用于往返相等性比较的结构化真相来源。
 *
 * <p>实现包括 {@code io.github.nondirectional.docx.core.api.text.Run}、{@code
 * io.github.nondirectional.docx.core.api.text.Hyperlink} 和 {@code
 * io.github.nondirectional.docx.core.api.image.Image}，将在后续阶段添加。 这目前是一个标记接口；随着领域模型的增长，公共成员可能会被提升到其中。
 */
public interface InlineElement {}
