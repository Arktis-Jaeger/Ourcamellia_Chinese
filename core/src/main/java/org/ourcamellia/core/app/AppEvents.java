package org.ourcamellia.core.app;

import org.ourcamellia.common.domain.ConversationRef;
import org.ourcamellia.common.domain.Message;
import org.ourcamellia.common.domain.Notification;
import org.ourcamellia.common.domain.PeerStatus;

/**
 * 异步应用事件的接收器（回调职责：把应用层事件转交给呈现层）。
 * CLI 呈现层（Observatory 与 Old）实现本接口；应用层绝不直接操作终端。
 * 实现必须线程安全（网络线程会调用这些回调）。
 */
public interface AppEvents {

    /** 收到一条消息（入站）或确认已发送（出站）。 */
    void onMessage(ConversationRef conversation, Message message);

    /** 会话列表、状态区域或对端状态发生了变化。 */
    void onStatusChanged();

    /** 通知集合发生变化（新增、已处理、已消除、已过期）。 */
    void onNotificationsChanged();

    /** 供 UI 显示的一条瞬态信息行（错误、确认等）。 */
    void onInfo(String text);

    /** 某个对端的观察者相对状态发生了变化。 */
    void onPeerStatus(String peerName, PeerStatus status);

    /** 应用正在关闭（UI 应停止读取输入）。 */
    void onShutdown();
}
