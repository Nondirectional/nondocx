package io.github.nondirectional.docx.toolkit.capability;

/**
 * 能力声明违反。在 {@link CapabilityCollector} 收集期抛出，表示某工具的能力注解缺失或不一致。
 *
 * <p>这是<b>声明/构建期</b>错误（元数据不完整），不是文档运行时错误， 因此不进入 core 的 {@code DocxException} 层次，而继承 {@link
 * IllegalStateException}（与 toolkit 层 {@code RefResolutionException} 继承 {@code
 * IllegalArgumentException} 的模式一致）。
 *
 * <p>典型场景：
 *
 * <ul>
 *   <li>{@code @ToolDef} 方法缺少 {@code @ToolCapability}。
 *   <li>{@code @ParamCapability.enumValues} 非空但 {@code type} 不是 ENUM。
 *   <li>{@code @ParamCapability} 标在非 {@code @ToolParam} 参数上。
 *   <li>{@code @NestedParamCapability.path} 首段与同位 {@code @ToolParam.name} 不一致。
 * </ul>
 */
public final class CapabilityDeclarationException extends IllegalStateException {

  public CapabilityDeclarationException(String message) {
    super(message);
  }

  public CapabilityDeclarationException(String message, Throwable cause) {
    super(message, cause);
  }
}
