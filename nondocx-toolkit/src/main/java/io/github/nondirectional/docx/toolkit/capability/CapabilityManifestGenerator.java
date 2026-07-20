package io.github.nondirectional.docx.toolkit.capability;

import io.github.nondirectional.docx.toolkit.DocxToolkit;
import io.github.nondirectional.docx.toolkit.capability.model.CapabilityManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 构建期能力清单生成器。
 *
 * <p>由 maven exec-maven-plugin 在 {@code process-classes} 阶段调用，输出 {@code capabilities.json} + {@code
 * capabilities.digest} 到指定目录（通常是 {@code target/classes}，打入 classpath）。
 *
 * <p>用法：{@code java -cp ... CapabilityManifestGenerator <输出目录>}
 *
 * <p><b>单一真实来源。</b> 本类不手写任何能力，全部从 Java 代码 + 注解反射收集。
 */
public final class CapabilityManifestGenerator {

  public static void main(String[] args) throws Exception {
    String outputDir = args.length > 0 ? args[0] : "target/classes";
    Path dir = Paths.get(outputDir);
    Files.createDirectories(dir);

    DocxToolkit tk = new DocxToolkit();
    CapabilityManifest manifest = tk.capability.collectManifest();

    // capabilities.json（格式化，便于人工审阅）
    Path jsonPath = dir.resolve("capabilities.json");
    Files.writeString(jsonPath, CapabilityJsonIo.toJson(manifest));

    // capabilities.digest（单行摘要，供 Agent 缓存判断）
    Path digestPath = dir.resolve("capabilities.digest");
    Files.writeString(digestPath, manifest.digest() + "\n");

    System.out.println(
        "已生成能力清单: "
            + jsonPath
            + " ("
            + manifest.tools().size()
            + " 个工具, digest="
            + manifest.digest().substring(0, Math.min(16, manifest.digest().length()))
            + "...)");
    System.out.println("digest 文件: " + digestPath);
  }

  private CapabilityManifestGenerator() {}
}
