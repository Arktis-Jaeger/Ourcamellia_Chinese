package org.ourcamellia.core.app;

import org.ourcamellia.common.Errors;
import org.ourcamellia.common.protocol.Envelope;
import org.ourcamellia.common.protocol.Wire;
import org.ourcamellia.core.transport.Conn;

/**
 * 服务器模式协议帧的客户端处理（登录后）：好友、私聊、群组（含加密群组密钥包装）、
 * 通知与公告频道。收到的一切内容只按帧处理，绝不当成命令解析。
 */
final class ServerProtocol {

    private ServerProtocol() {}

    /** 按帧类型分发给 ServerSession 处理；帧中的用户名/正文等一律原样透传。 */
    static void handle(ServerSession s, Conn conn, Envelope envelope) {
        switch (envelope.type()) {
            case Wire.T_WELCOME -> {
                var w = Wire.parseWelcome(envelope);
                s.setWelcome(w.serverName(), w.channelId(), w.unreadNotifications(),
                        w.registrationPolicy(), w.groupPolicyNote());
            }
            case Wire.T_REGISTER_RESULT -> {
                var r = Wire.parseRegisterResult(envelope);
                s.onRegisterResult(r.status(), r.ticketId(), r.message());
            }
            case Wire.T_AUTH_OK -> {
                var a = Wire.parseAuthOk(envelope);
                s.setLoggedIn(a.username(), a.bot(), a.unread());
            }
            case Wire.T_NOTIFY_LIST -> {
                var list = Wire.parseNotifyList(envelope);
                s.onNotifications(list.items()); // 登录后的离线通知批量投递
            }
            case Wire.T_NOTIFY_PUSH -> {
                var push = Wire.parseNotifyPush(envelope);
                s.onNotification(push.item()); // 在线实时通知推送
            }
            case Wire.T_NOTIFY_STATE -> {
                var state = Wire.parseNotifyState(envelope);
                s.onNotificationState(state.notifId(), state.state());
            }
            case Wire.T_FRIENDS_LIST -> {
                var friends = Wire.parseFriendsList(envelope);
                s.onFriendsList(friends.friends());
            }
            case Wire.T_PEER_STATE -> {
                var ps = Wire.parsePeerState(envelope);
                s.onPeerState(ps.user(), ps.status());
            }
            case Wire.T_FRIEND_STATE -> {
                var fs = Wire.parseFriendState(envelope);
                s.onFriendState(fs);
            }
            case Wire.T_MESSAGE -> {
                var m = Wire.parseMessage(envelope);
                s.onPrivateMessage(m);
            }
            case Wire.T_DELIVERY -> {
                // 私聊送达回执：status 为服务器返回的送达状态（delivered/failed 等）。
                var d = Wire.parseDelivery(envelope);
                s.events().onInfo("投递到 " + d.to() + "：" + d.status());
            }
            case Wire.T_GROUP_UPDATE -> {
                var u = Wire.parseGroupUpdate(envelope);
                s.onGroupUpdate(u.group());
            }
            case Wire.T_GROUP_MESSAGE -> {
                var m = Wire.parseGroupMessage(envelope);
                s.onGroupMessage(m);
            }
            case Wire.T_GROUP_KEY_WRAP -> {
                var w = Wire.parseGroupKeyWrap(envelope);
                s.onGroupKeyWrap(w);
            }
            case Wire.T_CHANNEL_MESSAGE -> {
                var m = Wire.parseChannelMessage(envelope);
                s.onChannelMessage(m);
            }
            case Wire.T_MOD_DELETE -> {
                // 审核删除：管理员移除了群组中的某条消息，提示用户。
                var d = Wire.parseModDelete(envelope);
                s.events().onInfo("运营者（管理员）已删除群组 " + d.groupId()
                        + " 中的一条消息（消息 " + d.msgId() + "）");
            }
            case Wire.T_ERROR -> s.events().onInfo(
                    "服务器报告：" + envelope.body().path("message").asText("error"));
            case Wire.T_ACK -> { /* 服务器确认帧，客户端无需动作 */ }
            default -> conn.send(Wire.error(envelope.id(), Errors.Category.USAGE, "不支持的帧类型"));
        }
    }
}
