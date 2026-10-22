package org.ourcamellia.client;

import org.ourcamellia.core.cmd.CommandDef;
import org.ourcamellia.core.cmd.CommandDispatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成 ${help} 全屏帮助页的内容（未按终端宽度折行）。
 *
 * <p>帮助内容由三部分组成：
 * <ol>
 *   <li>当前版本支持的键盘操作与输入规则；</li>
 *   <li>配置项说明（terminal.mode、terminal.shiftEnterToSend）；</li>
 *   <li>命令列表——直接来自已安装的 {@link CommandDispatcher}，而不是另写一份
 *       可能过时的命令表，保证帮助与实际可执行命令一致。</li>
 * </ol>
 */
final class HelpContent {

    private HelpContent() {}

    static List<String> build(CommandDispatcher dispatcher, CommandDef.Context context,
                              String terminalType, boolean shiftEnterToSend, String dataDir,
                              String terminalMode) {
        List<String> lines = new ArrayList<>();
        lines.add("键盘操作");
        lines.add("  ↑ / ↓        在菜单、列表中移动焦点；在帮助页滚动");
        lines.add("  Enter        确认当前选项；在消息输入中换行或发送（见下）");
        lines.add("  Shift+Enter  与 Enter 相反：默认发送；配置为 false 时换行");
        lines.add("  Tab          表单中切换到下一字段；聊天中切换消息输入与命令栏");
        lines.add("  Shift+Tab    表单中切换到上一字段");
        lines.add("  Esc          返回上一级；命令栏有内容时先清空命令栏");
        lines.add("  PgUp/PgDn    滚动消息记录或帮助内容");
        lines.add("  Ctrl+S       发送消息（终端无法区分 Shift+Enter 时的替代方式）");
        lines.add("  Ctrl+C       清空当前输入行（不会退出程序）");
        lines.add("  I            在会话列表中打开通知（仅当命令栏为空）");
        lines.add("");
        lines.add("输入规则");
        lines.add("  · 菜单导航与命令输入栏同时存在：↑↓ 选择项目，命令行输入命令。");
        lines.add("  · 只有整行以一个 ${ 开头并以 } 结束的内容才会被当作命令。");
        lines.add("  · 普通聊天内容不会被从中间提取成命令；命令栏中的普通文本不会发送。");
        lines.add("  · 消息输入支持多行；发送后只清空已发送内容。");
        lines.add("  · 返回会话列表时，未发送的消息会作为草稿保留，重新打开会话时恢复。");
        lines.add("  · 草稿只保存在本次运行的内存中；退出程序前会提示存在未发送草稿。");
        lines.add("  · 在消息输入区输入 ${help} 会打开本页，而不会被发送给对方。");
        lines.add("  · ${help} 打开本页；按 Enter 返回进入帮助前的页面和焦点。");
        lines.add("");
        lines.add("当前配置");
        lines.add("  terminal.mode            = " + terminalMode);
        lines.add("  terminal.shiftEnterToSend = " + shiftEnterToSend
                + (shiftEnterToSend ? "（Enter 换行，Shift+Enter 发送）" : "（Enter 发送，Shift+Enter 换行）"));
        lines.add("  终端类型                 = " + terminalType);
        lines.add("  数据目录                 = " + dataDir);
        lines.add("");
        lines.add("限制与说明");
        lines.add("  · 部分终端把 Shift+Enter 与 Enter 发送成同一个字节。此时 Shift+Enter");
        lines.add("    无法被识别，请使用 Ctrl+S 发送；界面不会假装组合键已经生效。");
        lines.add("  · Old 模式（terminal.mode=OLD）仍然是逐行输出，不使用固定布局；");
        lines.add("    它支持消息中的 \\n 换行与 \\${ 字面量转义。");
        lines.add("  · 本页为只读；连接、会话与记录状态不会因打开帮助而改变。");
        lines.add("");
        lines.add("命令列表（当前环境）");
        String helpText = dispatcher.helpText(context);
        for (String line : helpText.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            lines.add("  " + line);
        }
        lines.add("");
        lines.add("命令参数形式：${命令名(参数, ...)}；字符串参数使用双引号，例如");
        lines.add("  ${mode(\"local\", \"alice\")}   ${say(\"hello\")}   ${record(\"off\")}");
        return lines;
    }
}
