package org.ourcamellia.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Observatory 模式回归测试（缺陷 A / 缺陷 B）：
 *
 * <p>在一个真实伪终端（PTY）中启动 observatory 客户端，验证：
 * <ol>
 *   <li>键入能实时回显在屏幕上（缺陷 A：旧实现 input.available() 恒为 0，
 *       键入完全不生效 —— 本测试确认真实终端输入泵有效）；</li>
 *   <li>密集 SIGWINCH 缩放（含极端行列尺寸）不会使进程崩溃（缺陷 B：旧实现
 *       渲染 StringIndexOutOfBoundsException 直接杀死进程）；</li>
 *   <li>${quit} 依然能正常退出且退出码为 0。</li>
 * </ol>
 *
 * <p>该测试依赖 python3 的 pty/fcntl/termios（仅 POSIX 主机可用）；在缺少
 * python3 或 pty 的环境（如部分沙箱/CI）下自动跳过。
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
    void observatoryTypingResizeAndQuitUnderPty(@TempDir Path tmp) throws Exception {
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
        boolean exited = p.waitFor(60, TimeUnit.SECONDS);
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
     * 2) 等待菜单提示出现；
     * 3) 键入 "你好，世界" 并确认回显出现在屏幕上（缺陷 A 回归）；
     * 4) 连续发送 60 次 SIGWINCH（行列尺寸含极端值），进程必须存活（缺陷 B 回归）；
     * 5) 收窄到极小窗口后仍能键入且不崩溃；
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
                # 沙箱/宿主无可用 PTY 设备：按环境不支持跳过（退出码 42 由 Java 侧识别）。
                print("SKIP: no pty device available", flush=True)
                sys.exit(42)
            set_winsize(master, 24, 90)
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
                            data = os.read(master, 4096)
                        except OSError:
                            return
                        if not data:
                            return
                        out.extend(data)

            def wait_for(token, ms=15000):
                tb = token.encode("utf-8")
                end = time.time() + ms / 1000.0
                while time.time() < end:
                    pump(100)
                    if tb in out:
                        return True
                return False

            def send(s):
                os.write(master, s.encode("utf-8"))

            if not wait_for("(菜单)"):
                print("FAIL: 菜单提示未出现", flush=True)
                print(out.decode("utf-8", "replace"), flush=True)
                sys.exit(1)
            print("STEP menu-ok", flush=True)

            # 缺陷 A 回归：键入必须实时回显
            send("你好，世界")
            if not wait_for("你好，世界", 8000):
                print("FAIL: 键入内容未回显（输入泵失效）", flush=True)
                print(out.decode("utf-8", "replace"), flush=True)
                sys.exit(2)
            print("STEP echo-ok", flush=True)
            send(chr(13))  # 提交本次输入（菜单视图下消息/错误不可见属原设计），清空输入行

            # 缺陷 B 回归：密集 WINCH（含极端尺寸）不得崩溃
            for i in range(60):
                rows = 2 + (i % 46)
                cols = 4 + (i % 120)
                set_winsize(master, rows, cols)
                os.kill(p.pid, signal.SIGWINCH)
                pump(25)
                if p.poll() is not None:
                    print("FAIL: 缩放过程中进程提前退出 rc=%s" % p.returncode, flush=True)
                    print(out.decode("utf-8", "replace"), flush=True)
                    sys.exit(3)
            print("STEP resize-ok", flush=True)

            # 极端窄窗口下继续键入与回显
            set_winsize(master, 5, 12)
            os.kill(p.pid, signal.SIGWINCH)
            pump(200)
            send("ok")
            if p.poll() is not None:
                print("FAIL: 窄窗口后进程退出 rc=%s" % p.returncode, flush=True)
                print(out.decode("utf-8", "replace"), flush=True)
                sys.exit(4)
            print("STEP narrow-ok", flush=True)
            send(chr(13))  # 提交并清空输入行

            # 正常退出（须先清空输入行，${quit} 才能作为独立命令执行）
            send("${quit}" + chr(13))
            try:
                rc = p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                print("FAIL: ${quit} 未退出", flush=True)
                print(out.decode("utf-8", "replace"), flush=True)
                sys.exit(5)
            if rc != 0:
                print("FAIL: 退出码=%s" % rc, flush=True)
                print(out.decode("utf-8", "replace"), flush=True)
                sys.exit(6)
            print("RESULT:OK", flush=True)
            sys.exit(0)
            """;
}