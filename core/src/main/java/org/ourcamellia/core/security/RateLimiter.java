package org.ourcamellia.core.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 以任意字符串为键（连接 ID、IP、账户、BOT 令牌）的令牌桶限流器。
 * 仅保存在内存中——限制按进程生效，文档已注明。方法返回布尔值；
 * 调用方将拒绝转换为 RateLimited 错误。
 */
public final class RateLimiter {

    private final int permitsPerMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int permitsPerMinute) {
        this.permitsPerMinute = Math.max(1, permitsPerMinute);
    }

    // 令牌桶算法：按经过的时间平滑补充令牌，桶容量=每分钟许可数；
    // 有令牌则放行并扣减，无令牌则拒绝。每个 key 独立的桶，桶内加锁。
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(now, permitsPerMinute));
        synchronized (b) {
            // 按流逝时间补充令牌（不超过桶容量）
            long elapsed = now - b.lastRefill;
            double refill = elapsed * (permitsPerMinute / 60_000.0);
            b.tokens = Math.min(permitsPerMinute, b.tokens + refill);
            b.lastRefill = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return true;
            }
            return false;
        }
    }

    public void clear(String key) {
        buckets.remove(key);
    }

    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(long now, double full) {
            this.tokens = full; // 首次见到该 key 时给满额度
            this.lastRefill = now;
        }
    }
}
