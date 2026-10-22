package org.ourcamellia.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Observatory 模式 PTY 回归测试。
 *
 * <p>在真实伪终端中启动客户端，验证：
 * <ol>
 *   <li>菜单可用 ↑↓ 移动焦点、Enter 确认；</li>
 *   <li>表单输入与本地模式列表出现；</li>
 *   <li>I 快捷键打开通知，Esc 返回；</li>
 *   <li>${help} 打开全屏帮助页，Enter 返回原页面；</li>
 *   <li>密集 SIGWINCH（含极窄/极矮尺寸）不会崩溃；</li>
 *   <li>${quit} 正常退出，退出码 0。</li>
 * </ol>
 *
 * <p>该测试依赖 python3 的 pty/fcntl/termios（仅 POSIX 主机可用）；在缺少
 * python3 或 PTY 设备的环境（如受限沙箱）下自动跳过。
 * 仅当已构建 client 的 shaded jar（即执行过 mvn package）时才运行。
 */
class ObservatoryPtyIT {

    private static final String PYTHON = findPython();

    private static String findPython() {
        for (String candidate : new String[]{
                "/Library/Frameworks/Python.framework/Versions/3.10/bin/python3",
                "/usr/bin/python3", "/usr/local/bin/python3", "python3"}) {
            try {
                Process p = new ProcessBuilder(candidate, "-c", "import pty,fcntl,termios,os")
                        .redirectErrorStream(true).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Test
    void observatoryMenuHelpResizeAndQuitUnderPty(@TempDir Path tmp) throws Exception {
        String python = ObservatoryPtyIT.PYTHON;
        assumeTrue(python != null, "本环境无可用 python3（需要 pty/fcntl/termios）——跳过");
        Path clientJar = Path.of("..", "client", "target", "ourcamellia-client.jar");
        assumeTrue(Files.exists(clientJar), "shaded client jar not built yet — run with `mvn package`");

        Path data = tmp.resolve("obs-data");
        Path driver = tmp.resolve("obs_driver.py");
        Files.writeString(driver, DRIVER_TEXT);

        Process p = new ProcessBuilder(python, driver.toAbsolutePath().toString(),
                clientJar.toAbsolutePath().toString(), data.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .directory(tmp.toFile())
                .start();
        StringBuilder log = new StringBuilder();
        byte[] buf = new byte[4096];
        while (true) {
            int n = p.getInputStream().read(buf);
            if (n < 0) {
                break;
            }
            log.append(new String(buf, 0, n));
        }
        boolean exited = p.waitFor(90, TimeUnit.SECONDS);
        assertTrue(exited, "driver 超时, 日志:\n" + log);
        int rc = p.exitValue();
        // 驱动约定：退出码 42 = 主机无可用 PTY 设备（如沙箱限制 /dev/pty），按环境不支持跳过。
        if (rc == 42) {
            System.out.println("跳过：本环境无可用 PTY 设备（驱动输出如下）：\n" + log);
            assumeTrue(false, "环境不支持 PTY（无可用 /dev/pty 设备）——跳过");
            return;
        }
        assertEquals(0, rc, "PTY 驱动失败, 日志:\n" + log);
        assertTrue(log.toString().contains("RESULT:OK"), "驱动未报告成功, 日志:\n" + log);
    }

    /**
     * 内嵌 PTY 驱动脚本（UTF-8）：
     * 1) 以 PTY 启动 client（--mode observatory），设置控制终端与 TERM；
     * 2) 等待“连接方式”；↑↓ 改变焦点并用 Enter 进入局域网模式；
     * 3) 输入本地用户名后进入会话列表；
     * 4) I 打开通知、Esc 返回；${help} 打开帮助、Enter 返回；
     * 5) 连续发送 60 次 SIGWINCH（含极端行列尺寸），再恢复为 100x30；
     * 6) 输入 ${quit}，进程以 0 退出。
     */
    private static final String DRIVER_TEXT = """
            # -*- coding: utf-8 -*-
            import os, sys, pty, time, select, fcntl, termios, struct, signal, subprocess

            jar, data = sys.argv[1], sys.argv[2]

            def set_winsize(fd, rows, cols):
                fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

            def child_setup(slave):
                os.setsid()
                fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

            env = dict(os.environ)
            env["TERM"] = "xterm-256color"
            try:
                master, slave = pty.openpty()
            except OSError:
                print("SKIP: no pty device available", flush=True)
                sys.exit(42)
            set_winsize(master, 30, 100)
            p = subprocess.Popen(
                ["java", "-jar", jar, "--mode", "observatory", "--data-dir", data],
                stdin=slave, stdout=slave, stderr=slave, env=env, close_fds=True,
                preexec_fn=lambda: child_setup(slave),
            )
            os.close(slave)
            out = bytearray()

            def pump(ms):
                end = time.time() + ms / 1000.0
                while time.time() < end:
                    r, _, _ = select.select([master], [], [], 0.05)
                    if r:
                        try:
                            data = os.read(master, 65536)
                        except OSError:
                            return
                        if not data:
                            return
                        out.extend(data)

            def wait_for(token, ms=15000, start=0):
                tb = token.encode("utf-8")
                end = time.time() + ms / 1000.0
                while time.time() < end:
                    pump(100)
                    if tb in bytes(out[start:]):
                        return True
                return False

            def send(s):
                os.write(master, s.encode("utf-8"))

            def fail(code, msg):
                print("FAIL: " + msg, flush=True)
                print(out.decode("utf-8", "replace"), flush=True)
                sys.exit(code)

            if not wait_for("连接方式"):
                fail(1, "主菜单未出现")
            print("STEP menu-ok", flush=True)

            # ↑↓ 必须真的移动焦点，而不是静态打印 >
            send("\\x1b[B")
            pump(300)
            if "> 局域网模式".encode("utf-8") not in bytes(out):
                fail(2, "焦点未随 ↓ 移动")
            print("STEP focus-ok", flush=True)

            send("\\r")
            if not wait_for("局域网登录"):
                fail(3, "局域网登录页未出现")
            send("tester")
            pump(300)
            send("\\r")
            if not wait_for("本地@"):
                fail(4, "本地会话列表未出现")
            print("STEP local-ok", flush=True)

            send("I")
            if not wait_for("活动通知"):
                fail(5, "通知页未出现")
            mark = len(out)
            send("\\x1b")
            if not wait_for("会话列表", start=mark):
                fail(6, "Esc 未返回会话列表")
            print("STEP notifications-ok", flush=True)

            mark = len(out)
            send("${help}\\r")
            if not wait_for("键盘操作", start=mark):
                fail(7, "${help} 未打开帮助页")
            mark = len(out)
            send("\\r")
            if not wait_for("会话列表", start=mark):
                fail(8, "帮助页 Enter 未返回原页面")
            print("STEP help-ok", flush=True)

            # 密集 WINCH（含极端尺寸）不得崩溃
            for i in range(60):
                rows = 2 + (i % 46)
                cols = 4 + (i % 120)
                set_winsize(master, rows, cols)
                os.kill(p.pid, signal.SIGWINCH)
                pump(25)
                if p.poll() is not None:
                    fail(9, "缩放过程中进程提前退出 rc=%s" % p.returncode)
            print("STEP resize-ok", flush=True)

            set_winsize(master, 30, 100)
            os.kill(p.pid, signal.SIGWINCH)
            mark = len(out)
            if not wait_for("本地@", start=mark):
                fail(10, "恢复尺寸后未重新绘制列表")
            def wait_exit(ms):
                end = time.time() + ms / 1000.0
                while time.time() < end:
                    pump(100)
                    rc = p.poll()
                    if rc is not None:
                        return rc
                return None

            pump(2000)
            send("${quit}\\r")
            rc = wait_exit(20000)
            if rc is None:
                fail(11, "${quit} 未退出")
            if rc != 0:
                fail(12, "退出码=%s" % rc)
            print("RESULT:OK", flush=True)
            sys.exit(0)
            """;
}
