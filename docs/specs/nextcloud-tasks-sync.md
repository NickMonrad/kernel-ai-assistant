# Nextcloud Tasks sync

> **Status:** Authoritative provider-specific subsystem behaviour spec for the shipped #1539/#1551 Nextcloud Lists integration.
>
> **Base contract:** This provider layers on the transport-independent [`shared-lists-sync.md`](./shared-lists-sync.md) contract.

Jandal Lists supports one explicit Nextcloud account through standard CalDAV and VTODO operations. Only explicitly selected/bound Lists participate in provider sync; unbound Lists remain local-only.

## Account and discovery

The Settings screen stores the server URL, username, Nextcloud app password and the per-account
insecure-HTTP opt-in in one Keystore-backed encrypted preference record. The password is not logged
or included in provider metadata, and it is never read back into UI state, so a saved password is
never redisplayed; credential fields appear only for initial connection or an explicit
edit/reconnect. An expired credential surfaces an account-level `Reconnect` while local Lists stay
usable.

The connection form takes a **Nextcloud address**: a bare host, a host plus base path, a host plus
port, or an explicit URL. HTTPS is always the default and is applied when no scheme is supplied. An
explicit `http://` address is rejected inline while the unchecked **Use insecure HTTP** option is
off, and an HTTPS endpoint is never silently downgraded to HTTP without that opt-in; ports and base
paths survive normalization.

Redirects are evaluated hop by hop. A redirect to another host or port is refused before the saved Basic credentials can be sent outside the configured credential origin. Same-origin scheme changes still follow the HTTPS/insecure-HTTP policy above, so an HTTPS-to-HTTP downgrade requires the explicit opt-in.

Discovery follows `/.well-known/caldav`, then DAV `current-user-principal`, `calendar-home-set`, and
calendar collections that advertise VTODO support. A connection failure is surfaced as an actionable
authentication, network, discovery, or malformed-response message.

## Bindings and identifiers

Binding is an explicit, one-to-one operation. Room stores provider metadata separately from the
stable Jandal collection/item IDs:

- collection href, title, ETag/logical clock, and per-list sync state;
- item UID, href, ETag, raw VTODO, and remote deletion state.

Adding an existing remote collection is idempotent. Local names stay the canonical title unless a
collision with another local list forces a generated alias. A remote collection created by Jandal
carries the Jandal list title as its visible `displayname`; only the internal href uses a generated
unique slug, so no implementation identifier can appear in a user-visible list name. Local aliases
and other local-only list fields are never sent to Nextcloud.

## Per-list sync lifecycle

The Nextcloud lists screen places every list in exactly one of **Connected lists**, **Nextcloud only** or **Jandal only**. Name search composes with the **All / Connected / Nextcloud only / Jandal only / Needs attention** filters. Connected rows expose the per-list states `Up to date`, `Syncing…`, `Sync off` and `Needs attention`, with retry available for a failed list.

**Sync with Nextcloud** remains available for a Jandal-only list even when no account is configured. Choosing it opens the address-first connection flow with that list pending; successful setup continues the binding automatically, while cancellation or failure leaves the list local-only without a partial provider binding.

**Stop Nextcloud sync** disables synchronization for one list only. The local list, its items, the remote collection and the provider association are all retained, so **Resume Nextcloud sync** reuses the same association and never creates a second remote collection. Deleting the remote collection is not part of stopping sync.

Synchronization is triggered by local changes, app-active refresh, manual refresh/retry, and the bounded WorkManager periodic job. A stopped list is skipped by later triggers until it is resumed.

## VTODO mapping

`SUMMARY`, `STATUS`, `DUE`, `RELATED-TO;RELTYPE=PARENT`, and
`X-JANDAL-ORDER` map to item text, checked state, due time, hierarchy, and ordering.
Unknown VTODO properties and unrelated relationships are retained from the last remote
representation when Jandal updates a task. Remote deletion creates a Jandal tombstone;
explicit local restore publishes the task again with an `If-None-Match: *` precondition.

## Writes, conflicts, and triggers

Room remains authoritative. Local mutations continue through the existing mutation,
change-log, and outbox path. Provider writes use ETag `If-Match`; new resources use
`If-None-Match: *`. A precondition failure pulls the current remote representation,
reconciles it through the normal import path, and retries once. Conflicts that remain
are reported without overwriting either side.

Manual refresh and per-list retry provide deterministic recovery in addition to the automatic local-change, app-active and WorkManager triggers (15-minute periodic work). No hosted relay, background production internet service, account system, or billing integration is part of this feature.

## Verification

The JVM tests cover VTODO preservation/escaping, CalDAV discovery, authentication
errors, duplicate-safe response handling, conditional writes, ETag parsing, address
normalization, the HTTPS-only redirect policy, clean remote display naming, and per-list
stop/resume. A real Nextcloud instance with an app password remains an environment-owned
interoperability gate; CI does not contain provider credentials.
