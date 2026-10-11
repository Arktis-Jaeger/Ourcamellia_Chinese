package org.ourcamellia.core.app;

import org.ourcamellia.common.config.ClientConfig;
import org.ourcamellia.core.persist.ClientStateStore;
import org.ourcamellia.core.persist.LocalHistoryStore;

import java.nio.file.Path;

/**
 * 工厂：让 CLI 在应用构造之后、任何 UI 启动之前注入应用事件。
 * 保留在 core 中，使测试与嵌入代码共用同一条路径。
 */
public final class ClientAppFactory {

    private ClientAppFactory() {}

    public static ClientApp create(ClientConfig config, ClientStateStore store,
                                   LocalHistoryStore history, Path dataDir) {
        return new ClientApp(config, store, history, new AppEvents() {
            @Override
            public void onMessage(org.ourcamellia.common.domain.ConversationRef conversation,
                                  org.ourcamellia.common.domain.Message message) {
                // 事件由 UI 在运行循环开始前注入。
            }

            @Override
            public void onStatusChanged() {
            }

            @Override
            public void onNotificationsChanged() {
            }

            @Override
            public void onInfo(String text) {
            }

            @Override
            public void onPeerStatus(String peerName, org.ourcamellia.common.domain.PeerStatus status) {
            }

            @Override
            public void onShutdown() {
            }
        }, dataDir);
    }

    /** 替换事件接收器（由 CLI 在运行循环前调用一次）。 */
    public static void attachEvents(ClientApp app, AppEvents events) {
        app.attachEvents(events);
    }
}
