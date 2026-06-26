package com.solar.launcher.soulseek;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConversationDisplayBuilderTest {

    @Test
    public void roomReactionUsesMessagePeerNotRoomPeerUsername() {
        List<SoulseekMessaging.Message> raw = Arrays.asList(
                new SoulseekMessaging.Message(1, 100, "alice", "hello room", true),
                new SoulseekMessaging.Message(2, 200, "bob",
                        ReachMessageFormat.formatReactionWire("hello room", "\uD83D\uDC4D"), true)
        );
        List<ConversationDisplayBuilder.Entry> entries =
                ConversationDisplayBuilder.build(raw, "me", null, true);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).reactionLines.get(0).contains("bob"));
    }
}
