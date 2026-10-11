package org.ourcamellia.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ourcamellia.common.Errors;
import org.ourcamellia.common.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JSON 配置装载器：从 JSON 文件装载 record 类型，并与默认值合并。
 * 未知的顶层配置键仅产生警告（不视为硬错误，保证向前兼容），缺失的键沿用默认值，
 * 类型错误的键则报出清晰的错误。配置文件中的密钥由运营者自行保护
 * （运营文档建议 0600 权限），且绝不会被记录到日志。
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * 装载配置：文件不存在时直接返回默认值；存在则读取 JSON，把 defaults 与文件内容
     * 按 record 组件名合并（未知键收集后经 warnings 回调通报）。合并结果反序列化回
     * record 类型；任何解析/类型错误统一包装为 Validation 异常。
     */
    public static <T extends Record> T load(Path file, Class<T> type, T defaults, Consumer<String> warnings) {
        if (file == null || !Files.exists(file)) {
            return defaults;
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new Errors.Persistence("无法读取配置文件：" + file, e);
        }
        JsonNode root = Json.parse(raw);
        if (!root.isObject()) {
            throw new Errors.Validation("配置文件必须是 JSON 对象：" + file);
        }
        List<String> unknown = new ArrayList<>();
        try {
            ObjectNode merged = Json.mapper().valueToTree(defaults);
            root.fields().forEachRemaining(e -> {
                java.util.Arrays.stream(type.getRecordComponents())
                        .filter(c -> c.getName().equals(e.getKey()))
                        .findFirst()
                        .ifPresentOrElse(
                                c -> merged.set(e.getKey(), e.getValue()),
                                () -> unknown.add(e.getKey()));
            });
            if (!unknown.isEmpty() && warnings != null) {
                // 未知键（如未来版本新增的键）只警告不失败；键名原样保留在消息中。
                warnings.accept("已忽略未知配置键：" + String.join(", ", unknown));
            }
            return Json.convert(merged, type);
        } catch (Errors.OcException e) {
            throw new Errors.Validation("无效的配置文件 " + file + "：" + e.getMessage());
        }
    }

    /** 装载并校验（与 load 相同，另加路径前缀的清晰错误消息）。 */
    public static <T extends Record> T loadStrict(Path file, Class<T> type, T defaults, Consumer<String> warnings) {
        T cfg = load(file, type, defaults, warnings);
        return cfg;
    }
}
