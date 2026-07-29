# Soulseek / Reach setup

Soulseek support extends Solar's existing Reach implementation; it is not a
replacement protocol client.

## Responsible-use gate

The first-use notice must be acknowledged before acquisition. Download only
material you own or are authorized to obtain. Solar does not bypass private
shares, access controls, payments, or peer restrictions.

## Configure an account

1. Connect the Y1 to Wi-Fi.
2. Open **Settings** and the **Soulseek/Reach account** entry.
3. Enter the account name and password with the wheel keyboard.
4. Save and wait for the connection-state row to report a successful session.
5. Open **Get Music** or **Reach**, enter a text query, and inspect source,
   peer, format, bitrate, size, and queue information before downloading.

Passwords are never included in transfer rows or diagnostic output. Do not post
screenshots containing credentials.

## Downloads

Soulseek jobs appear in the shared Downloads journal. Supported actions include
pause, resume, retry, cancel, remove/history cleanup, and partial-file recovery.
Completed audio is sanitized, checked for available storage, atomically
finalized where possible, and submitted to targeted library indexing.

If Wi-Fi disappears, the job should pause without deleting its partial file.
When enabled, Wi-Fi recovery resumes the same journal entry. A reboot turns
in-flight work into a recoverable paused state rather than inventing a second
download.

## Peer shares

Peer browsing and the local share index preserve the existing Soulseek access
model. Never publish private directories, credentials, backups, or application
data. Review the selected share roots and diagnostics before enabling sharing.

## Troubleshooting

- **Offline/authentication failed:** verify Wi-Fi diagnostics and account data;
  re-enter the password rather than sharing logs containing it.
- **Queued:** the peer controls its upload slots; wait or choose another lawful
  source.
- **Partial cannot resume:** retain the `.part` file and collect the job's safe
  failure reason before retrying.
- **Completed file absent from Music:** run the targeted library refresh and
  collect library/transfer diagnostics.
- **Repeated disconnect:** capture timestamps, peer-safe error state, Wi-Fi
  state, and app logs; do not include passwords or session material.

