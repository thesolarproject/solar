# Privacy

Solar is an on-device launcher and media application. Online features contact
only the service selected by the user, but Android 4.2 does not provide modern
hardware-backed application credential storage.

## Data kept on the player

- library metadata, playlists, favorites, playback/resume state;
- recent Get Music and YouTube metadata searches;
- download jobs, partial-file recovery data, and download history;
- deterministic Discover feedback and local-library ranking signals;
- Wi-Fi configuration references supplied by Android (never saved passwords in
  Solar diagnostics);
- Soulseek account/session data;
- Google device-flow access and refresh tokens when YouTube metadata is linked;
- optional third-party account settings already supported by Solar.

Google tokens and Soulseek credentials live in app-private storage and are
excluded from release logs and Solar diagnostic summaries. Root access,
physical image extraction, or a compromised system can still read them on this
API-17 device.

## Network use

Depending on enabled features, Solar may contact:

- the Soulseek network and selected peers;
- Podcast Index/RSS hosts and podcast enclosure servers;
- Google's OAuth and YouTube Data API endpoints;
- a user-entered direct-download host;
- a creator page only when the user requests creator-provided links;
- existing optional Solar providers such as Deezer or LALAL when configured.

YouTube support is metadata-only. Solar does not resolve or download YouTube
audio/video streams. Discover ranks cached/account metadata with local signals
on the player; local listening history is not uploaded for ranking.

Direct downloads reveal the requester's IP address and requested path to the
chosen host, as normal HTTP traffic does. Soulseek peers can observe protocol
information required by that network.

## User controls

The Settings and feature menus provide controls to:

- sign out/revoke the linked Google account;
- remove or replace Soulseek account data;
- clear Get Music/YouTube search and Discover feedback/history;
- clear completed transfer history and cached metadata;
- disable Wi-Fi auto-connect, Bluetooth auto-reconnect, or transfer auto-resume;
- delete downloaded or partial files through the relevant job action.

Automatic diagnostic reporting is disabled for this private test branch.
Review any manually exported diagnostic bundle before sharing it; filenames,
SSIDs, peer names, and media metadata may still be personally identifying even
when credentials are redacted.

## Responsible use

Acquire only media the user owns or has permission to download. Solar does not
bypass private shares, DRM, payment, age, regional, or access controls.

