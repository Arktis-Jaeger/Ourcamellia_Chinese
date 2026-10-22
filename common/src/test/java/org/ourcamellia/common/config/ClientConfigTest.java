package org.ourcamellia.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ourcamellia.common.Errors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置层回归测试：terminal.mode 的默认值/显式值/旧字段兼容，
 * shiftEnterToSend 默认值，以及便携式数据目录默认规则。
 */
class ClientConfigTest {

    @Test
    void defaultsAreObservatoryAndShiftEnterSends() {
        ClientConfig c = ClientConfig.defaults().validated();
        assertEquals("OBSERVATORY", c.terminal().mode());
        assertTrue(c.terminal().shiftEnterToSend());
        assertTrue(c.shiftEnterToSend());
        assertEquals("OBSERVATORY", c.effectiveTerminalMode());
    }

    @Test
    void nestedTerminalConfigIsLoaded(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("client.json");
        Files.writeString(f, """
                {"terminal":{"mode":"old","shiftEnterToSend":false}}
                """);
        List<String> warnings = new ArrayList<>();
        ClientConfig c = ConfigLoader.load(f, ClientConfig.class,
                ClientConfig.defaults(), warnings::add).validated();
        assertEquals("OLD", c.terminal().mode());
        assertFalse(c.shiftEnterToSend());
        assertEquals("OLD", c.effectiveTerminalMode());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void partialNestedConfigKeepsDefaultShiftEnterSetting(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("client.json");
        Files.writeString(f, "{\"terminal\":{\"mode\":\"OLD\"}}");
        ClientConfig c = ConfigLoader.load(f, ClientConfig.class,
                ClientConfig.defaults(), w -> {}).validated();
        assertEquals("OLD", c.terminal().mode());
        assertTrue(c.shiftEnterToSend(),
                "只写 terminal.mode 时，未写出的 shiftEnterToSend 必须保持默认 true");
    }

    @Test
    void legacyModeOldIsRespectedWhenTerminalModeIsDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("client.json");
        Files.writeString(f, "{\"mode\":\"old\"}");
        ClientConfig c = ConfigLoader.load(f, ClientConfig.class,
                ClientConfig.defaults(), w -> {}).validated();
        assertEquals("OLD", c.effectiveTerminalMode(),
                "升级后旧配置的 Old 模式不能被静默改成 Observatory");
    }

    @Test
    void explicitTerminalOldOverridesLegacyObservatory(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("client.json");
        Files.writeString(f, "{\"mode\":\"observatory\",\"terminal\":{\"mode\":\"OLD\"}}");
        ClientConfig c = ConfigLoader.load(f, ClientConfig.class,
                ClientConfig.defaults(), w -> {}).validated();
        assertEquals("OLD", c.effectiveTerminalMode());
    }

    @Test
    void invalidTerminalModeFails() {
        ClientConfig c = new ClientConfig("auto", "data", true, true, true, "0.0.0.0",
                54536, 54535, 60, 10_000, 90_000, 30_000, 1 << 20, false, 210_000, true, "info",
                new ClientConfig.Terminal("auto", true));
        assertThrows(Errors.Validation.class, c::validated);
    }

    @Test
    void defaultDataDirIsPortableNotHomeDotDirectory() {
        Path data = AppPaths.defaultDataDir();
        assertEquals("data", data.getFileName().toString());
        assertFalse(data.toString().contains(".ourcamellia"),
                "默认数据目录不应再依赖用户主目录");
        assertFalse(data.toString().contains("/target/"),
                "默认数据目录不能落在编译输出目录");
        assertFalse(data.toString().contains("/Study/"),
                "默认数据目录不能落在学习材料目录");
    }

    @Test
    void tildeIsExpanded() {
        String home = System.getProperty("user.home");
        assertEquals(Path.of(home).toAbsolutePath().normalize(),
                AppPaths.expandUserPath("~"));
        assertEquals(Path.of(home, "x").toAbsolutePath().normalize(),
                AppPaths.expandUserPath("~/x"));
    }
}
