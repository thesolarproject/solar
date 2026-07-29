# Official YouTube metadata setup

Solar uses the official YouTube Data API v3 for text metadata. It does not
resolve, scrape, play, or download YouTube audiovisual streams.

## Create the Google project

1. In Google Cloud Console, create or select a project owned by the device
   owner.
2. Enable **YouTube Data API v3** for that project.
3. Configure the OAuth consent screen. Add the device owner as a test user
   while the app remains in testing.
4. Create an API key for public metadata requests. Restrict the key to the
   YouTube Data API wherever the console permits.
5. Create an OAuth client whose application type is **TVs and Limited Input
   devices**. This is the client type used by Google's device authorization
   flow.

Google's limited-input guide is the source of truth for the flow and endpoints:
<https://developers.google.com/youtube/v3/guides/auth/devices>.

## Supply build credentials without committing them

Put these values in the user's Gradle properties file, not this repository:

Windows:

```text
%USERPROFILE%\.gradle\gradle.properties
```

Linux/macOS:

```text
~/.gradle/gradle.properties
```

Add:

```properties
YOUTUBE_DATA_API_KEY=replace-with-restricted-api-key
YOUTUBE_OAUTH_CLIENT_ID=replace-with-tv-client-id
YOUTUBE_OAUTH_CLIENT_SECRET=replace-with-tv-client-secret
```

The same names may be supplied as CI environment variables. Never add real
values to `gradle.properties`, source files, screenshots, issue reports, or
diagnostic bundles in the checkout.

Google currently requires `client_secret` when polling the device token
endpoint for this client type. A value distributed inside an APK cannot be kept
confidential, so Solar treats it only as a public client credential. It is
never described or relied upon as an application secret. User access and
refresh tokens are separate credentials and are never logged.

## Sign in on the Y1

1. Connect the Y1 to Wi-Fi.
2. Open YouTube metadata browse.
3. Select **Connect YouTube account**.
4. On a phone or computer, open the verification URL shown on the Y1 and enter
   the displayed code.
5. Leave the Y1 on that screen. Solar polls only at Google's supplied interval,
   adds five seconds when instructed to slow down, and stops at expiration,
   denial, cancellation, or success.

Solar requests only:

```text
https://www.googleapis.com/auth/youtube.readonly
```

Select the connected-account row again to revoke the token and erase Solar's
local copy.

## Security limitation on Android 4.2

The Y1's Android 4.2/API 17 platform predates the modern AES Android Keystore
facilities. Solar stores tokens in app-private preferences, disables Android
application-data backup (API 17 cannot selectively exclude just the credential
file), excludes tokens from logs/diagnostic bundles, and supports explicit
revocation. This protects them from ordinary apps, but not from root access or
offline extraction of a device image. Sign out before giving an unlocked or
rooted device to someone else.

## Metadata-only behavior

Search, regional popular metadata, duration, uploader, and public comments use
official API endpoints. Results offer only:

- save a local metadata bookmark;
- search the title/uploader through Solar's existing authorized Soulseek
  provider;
- display and copy the canonical YouTube URL.

Creator-provided downloads, podcast enclosures, and user-owned originals must
enter through separate import/download providers. They are never inferred from
or fetched through a YouTube media URL.
