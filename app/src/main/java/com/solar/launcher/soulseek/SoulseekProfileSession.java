package com.solar.launcher.soulseek;

/** Fetches a remote peer's profile via peer user-info request. */
public final class SoulseekProfileSession {

    public interface ProfileCallback {
        void onProfile(SoulseekWire.UserInfoResponse info);
        void onError(String reason);
    }

    private SoulseekProfileSession() {}

    public static void fetchProfile(final SoulseekClient client, final String peerUser,
                                    final ProfileCallback callback) {
        if (client == null || peerUser == null || peerUser.trim().isEmpty()) {
            if (callback != null) callback.onError("No user");
            return;
        }
        client.runPeerOperation(peerUser.trim(), new SoulseekClient.PeerOperation() {
            @Override
            public void run(java.net.Socket peer) throws Exception {
                peer.getOutputStream().write(
                        SoulseekWire.peerMessage(SoulseekWire.PEER_USER_INFO_REQUEST, new byte[0]));
                peer.getOutputStream().flush();
                SoulseekWire.PeerFrame frame = SoulseekWire.readPeerFrame(peer.getInputStream());
                if (frame.code != SoulseekWire.PEER_USER_INFO_RESPONSE) {
                    throw new java.io.IOException("Unexpected profile response");
                }
                final SoulseekWire.UserInfoResponse info =
                        SoulseekWire.parseUserInfoResponse(frame.body);
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("peerUser", peerUser);
                    d.put("descrLen", info != null && info.description != null
                            ? info.description.length() : 0);
                    d.put("bodyLen", frame.body != null ? frame.body.length : 0);
                    ReachDebugLog.log(null, "SoulseekProfileSession.fetchProfile",
                            "profile result", "H4-H5", d);
                } catch (Exception ignored) {}
                // #endregion
                client.notifyProfile(callback, info);
            }

            @Override
            public void onError(String reason) {
                // #region agent log
                try {
                    org.json.JSONObject d = new org.json.JSONObject();
                    d.put("peerUser", peerUser);
                    d.put("reason", reason != null ? reason : "");
                    ReachDebugLog.log(null, "SoulseekProfileSession.fetchProfile",
                            "profile error", "H3-H4", d);
                } catch (Exception ignored) {}
                // #endregion
                client.notifyProfileError(callback, reason);
            }
        });
    }
}
