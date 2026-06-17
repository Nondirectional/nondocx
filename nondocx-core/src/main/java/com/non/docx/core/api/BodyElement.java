package com.non.docx.core.api;

/**
 * 出现在文档正文中的顶级块，按文档顺序排列。
 *
 * <p>文档正文是一个有序的正文元素序列——主要是段落和表格。 {@code Document.bodyElements()} 返回它们在真实 Word
 * 正文顺序中的排列，此接口是该序列的共享类型。 它是用于往返相等性比较的结构化真相来源。
 *
 * <p>实现包括 {@code com.non.docx.core.api.text.Paragraph} 和 {@code
 * com.non.docx.core.api.table.Table}，将在后续阶段添加。这目前是一个标记接口； 随着领域模型的增长，公共成员可能会被提升到其中。
 */
public interface BodyElement {}
