package io.github.nondirectional.docx.toolkit.capability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** {@link NestedParamCapability} 的可重复容器。Java 8+ 反射机制要求。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface NestedParamCapabilities {
  NestedParamCapability[] value();
}
