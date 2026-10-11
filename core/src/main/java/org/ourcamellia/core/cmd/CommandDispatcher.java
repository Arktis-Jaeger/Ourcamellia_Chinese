package org.ourcamellia.core.cmd;

import org.ourcamellia.common.Errors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令分发：解析 → 注册表校验（参数个数、类型、可用环境）→ 处理器。
 * 业务逻辑位于装配到应用层的处理器中；分发器自身不执行业务规则
 * （ARCHITECTURE.md §4）。
 */
public final class CommandDispatcher {

    /** 命令实现。抛出任意 OcException 都会产生错误结果。 */
    public interface Handler {
        CommandResult handle(List<Arg> args, CommandDef.Context context) throws Errors.OcException;
    }

    private record Entry(CommandDef def, Handler handler) {}

    private final Map<String, Entry> commands = new LinkedHashMap<>();

    public void register(CommandDef def, Handler handler) {
        commands.put(def.name(), new Entry(def, handler));
    }

    public List<CommandDef> definitions() {
        List<CommandDef> out = new ArrayList<>();
        commands.values().forEach(e -> out.add(e.def()));
        return out;
    }

    public CommandDef definition(String name) {
        Entry e = commands.get(name);
        return e == null ? null : e.def();
    }

    /** 解析输入行；当其为命令时分发执行。 */
    public CommandResult execute(String line, CommandDef.Context context, boolean oldModeNewlineEscape) {
        ParsedInput in = CommandParser.parse(line, oldModeNewlineEscape);
        if (in instanceof ParsedInput.Command cmd) {
            return dispatch(cmd, context);
        }
        if (in instanceof ParsedInput.Malformed mal) {
            return CommandResult.error("命令格式有误 — " + mal.reason()
                    + "（未发送或执行任何内容）");
        }
        // 消息/空行由调用方处理（默认 say / 无操作）。
        return CommandResult.ok();
    }

    public CommandResult dispatch(ParsedInput.Command cmd, CommandDef.Context context) {
        Entry e = commands.get(cmd.name());
        if (e == null) {
            return CommandResult.error("未知命令 '" + cmd.name()
                    + "' — 使用 ${help} 查看命令列表");
        }
        CommandDef def = e.def();
        if (!def.validIn(context)) {
            return CommandResult.error("命令 '" + cmd.name() + "' 在当前环境中不可用");
        }
        if (cmd.args().size() < def.minArgs() || cmd.args().size() > def.maxArgs()) {
            return CommandResult.error("用法：" + def.usage());
        }
        for (int i = 0; i < cmd.args().size(); i++) {
            String typeError = typeCheck(def.paramAt(i).type(), cmd.args().get(i));
            if (typeError != null) {
                return CommandResult.error("参数 " + (i + 1) + "：" + typeError + " — 用法：" + def.usage());
            }
        }
        try {
            CommandResult r = e.handler().handle(cmd.args(), context);
            return r == null ? CommandResult.ok() : r;
        } catch (Errors.OcException ex) {
            return CommandResult.error(ex.getMessage());
        } catch (RuntimeException ex) {
            return CommandResult.error("内部错误：" + ex.getClass().getSimpleName());
        }
    }

    private static String typeCheck(CommandDef.ParamType type, Arg arg) {
        return switch (type) {
            case STRING -> null; // 任意参数按其字面文本呈现
            case NUMBER -> arg instanceof Arg.Num ? null : "应为数字";
            case BOOL -> arg instanceof Arg.Bool ? null : "应为 true 或 false";
            case ATOM, CHOICE -> arg instanceof Arg.Atom || arg instanceof Arg.Str
                    ? null : "应为名称或带引号的文本";
        };
    }

    /** 当前环境下所有可用命令的帮助文本。 */
    public String helpText(CommandDef.Context context) {
        StringBuilder sb = new StringBuilder("命令列表（当前环境）：\n");
        commands.values().stream()
                .map(Entry::def)
                .filter(d -> d.validIn(context))
                .forEach(d -> sb.append("  ${").append(d.name())
                        .append("}  —  ").append(d.summary()).append('\n'));
        return sb.toString().stripTrailing();
    }

    public String helpOne(String name) {
        Entry e = commands.get(name);
        if (e == null) {
            return "未知命令 '" + name + "'";
        }
        CommandDef d = e.def();
        StringBuilder sb = new StringBuilder(d.name()).append(" — ").append(d.summary()).append('\n')
                .append("用法：").append(d.usage()).append('\n');
        for (CommandDef.Param p : d.params()) {
            sb.append("  ").append(p.name()).append(" (").append(p.type())
                    .append(p.optional() ? "，可选" : "").append(")：")
                    .append(p.description()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}