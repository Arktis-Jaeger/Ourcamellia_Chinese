package org.ourcamellia.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Enter / Shift+Enter 两种配置语义，以及“同一次按键不会既换行又发送”。 */
class ChatInputTest {

    @Test
    void shiftEnterToSendTrue() {
        assertTrue(ChatInput.decideEnter(false, true) == ChatInput.Action.NEWLINE);
        assertTrue(ChatInput.decideEnter(true, true) == ChatInput.Action.SEND);
    }

    @Test
    void shiftEnterToSendFalse() {
        assertTrue(ChatInput.decideEnter(false, false) == ChatInput.Action.SEND);
        assertTrue(ChatInput.decideEnter(true, false) == ChatInput.Action.NEWLINE);
    }

    @Test
    void hintAlwaysMentionsCtrlSAlternative() {
        assertTrue(ChatInput.hint(true).contains("Ctrl+S"));
        assertTrue(ChatInput.hint(false).contains("Ctrl+S"));
    }
}
