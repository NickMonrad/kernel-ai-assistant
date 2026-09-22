# Nextcloud Tasks sync

Jandal Lists supports one explicit Nextcloud account through standard CalDAV and VTODO operations.

## Account and discovery

The Settings screen stores the server URL, username, and Nextcloud app password in one
Keystore-backed encrypted preference record. The password is not logged or included in
provider metadata. Discovery follows `/.well-known/caldav`, then DAV
`current-user-principal`, `calendar-home-set`, and calendar collections that advertise
VTODO support. A connection failure is surfaced as an actionable authentication,
network, discovery, or malformed-response message.

## Bindings and identifiers

Import and publish are explicit, one-to-one operations. Room stores provider metadata
separately from the stable Jandal collection/item IDs:

- collection href, title, ETag/logical clock;
- item UID, href, ETag, raw VTODO, and remote deletion state.

Importing the same remote collection is idempotent. Imported local names use a
`(Nextcloud)` display alias while the remote collection title remains canonical. Local
aliases and other local-only list fields are never sent to Nextcloud.

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

Sync can be started manually from Settings, immediately after pending shared-list
changes, or by a connected WorkManager periodic job (15 minutes). No hosted relay,
background production internet service, account system, or billing integration is part
of this feature.

## Verification

The JVM tests cover VTODO preservation/escaping, CalDAV discovery, authentication
errors, duplicate-safe response handling, conditional writes, and ETag parsing. A real
Nextcloud instance with an app password remains an environment-owned interoperability
gate; CI does not contain provider credentials.
