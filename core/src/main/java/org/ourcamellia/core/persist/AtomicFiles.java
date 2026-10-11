package org.ourcamellia.core.persist;

import org.ourcamellia.common.Errors;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 健壮的文件写入：先写同目录下的临时文件并 fsync，再原子替换目标文件
 * （在文件系统不支持原子移动时回退为非原子替换）。这样旧的损坏文件永远不会
 * 变成写了一半的状态文件。
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    // 原子写：临时文件 + fsync + 原子移动替换；任何一步失败都清理临时文件
    public static void write(Path target, byte[] content) {
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) {
            throw new Errors.Persistence("无法解析 " + target + " 的所在目录");
        }
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".tmp-" + target.getFileName(), ".part");
            try {
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    ch.write(java.nio.ByteBuffer.wrap(content));
                    ch.force(true);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    // 文件系统不支持原子移动：退化为普通替换
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new Errors.Persistence("无法写入 " + target, e);
        }
    }

    public static void writeString(Path target, String content) {
        write(target, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 读取文件字节；文件不存在时返回 null。 */
    public static byte[] readOrNull(Path target) {
        try {
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new Errors.Persistence("无法读取 " + target, e);
        }
    }

    /**
     * 损坏文件隔离：把坏文件移到一边（绝不静默删除可恢复的数据），
     * 以便人工之后检查。
     */
    public static void quarantine(Path target, String reason) {
        try {
            if (!Files.exists(target)) {
                return;
            }
            Path q = target.resolveSibling(target.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(target, q, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 隔离是尽力而为；原始错误仍会传递给调用方。
        }
    }
}
