package org.ourcamellia.common.config;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用位置解析：决定「程序所在目录」与默认数据目录。
 *
 * <p>本类解决的问题：Ourcamellia 强调便携运行，聊天记录必须保存在程序目录旁，
 * 而不是用户主目录。但同一个可执行程序可能以三种方式启动：
 * <ul>
 *   <li>打包运行：从发行目录中的 shaded jar 启动（例如 <code>app/ourcamellia-client.jar</code>）；</li>
 *   <li>开发运行：从 Maven 的 <code>target/classes</code> 启动；</li>
 *   <li>IDE 运行：类路径由 IDE 生成，工作目录可能不是项目根目录。</li>
 * </ul>
 *
 * <p>解析顺序（每一步都有明确依据，不依赖用户“碰巧”在哪个目录输入命令）：
 * <ol>
 *   <li>找到本类被加载的位置（code source）。</li>
 *   <li>如果该位置位于 Maven 的 <code>target/</code> 下，说明是开发运行；从该位置
 *       向上查找带 <code>ourcamellia-parent</code> 的 <code>pom.xml</code>，以该
 *       项目根目录作为程序目录。</li>
 *   <li>如果该位置是 <code>target/</code> 之外的 jar，则把 jar 所在目录当作发行目录。</li>
 *   <li>如果以上都不可用，才退回到“从当前工作目录向上查找项目根”；再不行才使用
 *       当前工作目录。该兜底不会被静默用于已有项目目录，且调用方在创建数据目录前
 *       还会检查目录是否可写。</li>
 * </ol>
 *
 * <p>默认数据目录固定为 <code>&lt;程序目录&gt;/data</code>，不会使用
 * <code>~/.ourcamellia</code>，也不会写入 <code>target/</code> 或
 * <code>Study/</code>（后两者由客户端启动代码在启动时再次拒绝）。
 */
public final class AppPaths {

    private static final Path APP_HOME = resolveAppHome();
    private static final Path DEFAULT_DATA_DIR = APP_HOME.resolve("data");

    private AppPaths() {}

    /** 程序目录：打包运行时是发行目录，开发运行时是 Maven 项目根目录。 */
    public static Path appHome() {
        return APP_HOME;
    }

    /** 默认数据目录：程序目录下的 data/。 */
    public static Path defaultDataDir() {
        return DEFAULT_DATA_DIR;
    }

    /**
     * 把配置中的路径展开为用户绝对路径。
     *
     * <p>历史配置文件可能写有 <code>~/.ourcamellia</code>；Java 的
     * {@link Path#of(String)} 不会展开波浪号，若不处理会创建一个名为
     * <code>~</code> 的相对目录。这里只处理“以 ~ 或 ~/ 开头”的写法，
     * 其他相对路径按当前工作目录解释（显式配置由用户负责）。
     */
    public static Path expandUserPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DATA_DIR;
        }
        String value = raw;
        String home = System.getProperty("user.home");
        if (home != null) {
            if (value.equals("~")) {
                value = home;
            } else if (value.startsWith("~/") || value.startsWith("~" + java.io.File.separator)) {
                value = home + value.substring(1);
            }
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path resolveAppHome() {
        Path codeSource = codeSourceLocation();
        if (codeSource != null) {
            Path dir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (dir != null) {
                Path projectRoot = findProjectRoot(dir);
                if (projectRoot != null) {
                    return projectRoot;
                }
                // 发行版：jar 位于某个目录中，且该目录不在 target/ 下。
                if (!isDirectory(codeSource) && !containsSegment(codeSource, "target")) {
                    return dir.toAbsolutePath().normalize();
                }
            }
        }
        Path fromCwd = findProjectRoot(Path.of("").toAbsolutePath().normalize());
        if (fromCwd != null) {
            return fromCwd;
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Path codeSourceLocation() {
        try {
            var source = AppPaths.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isDirectory(Path p) {
        // 不使用 Files.isDirectory 依赖文件系统状态，避免测试环境中的临时路径问题；
        // 扩展名不是 .jar 时按目录处理。
        return !p.getFileName().toString().endsWith(".jar");
    }

    /** 从给定目录向上查找本项目根目录（pom.xml 中含 ourcamellia-parent）。 */
    private static Path findProjectRoot(Path start) {
        Path current = start;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom) && isOurcamelliaParentPom(pom)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isOurcamelliaParentPom(Path pom) {
        try {
            String text = Files.readString(pom);
            // 模块 pom 也会在 <parent> 中引用 ourcamellia-parent；只有项目根 pom
            // 同时具有 packaging=pom 与 modules，才是我们要找的根目录。
            return text.contains("<artifactId>ourcamellia-parent</artifactId>")
                    && text.contains("<packaging>pom</packaging>")
                    && text.contains("<modules>");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsSegment(Path path, String segment) {
        List<String> parts = new ArrayList<>();
        for (Path p : path) {
            parts.add(p.toString());
        }
        return parts.contains(segment);
    }
}
