# Datasource secrets are encrypted at rest, under a key the operator must keep

Datasources are created from a web interface and survive restarts, so this server stores database
passwords. They are encrypted with AES-256-GCM under a key generated on first boot into
`${config-dir}/secret.key`, owner-readable only, and the REST API never returns a stored password —
an edit that leaves the field blank keeps the existing one.

Encryption at rest with the key in the same directory as the database it protects is not much of a
boundary, and it is not claimed as one: it stops a password being legible in a backup, a volume
snapshot, or a `SELECT * FROM datasource` by whoever is debugging. Anyone who can read the config
directory has both halves.

The alternative of not storing credentials at all — prompting the operator per restart, or reading
them from the environment — was rejected because it defeats the requirement that the server comes up
unattended and an agent finds its Datasources already working.

## Consequences

The key file is the backup-critical artifact. Losing it while keeping the database means every
Datasource must have its password re-entered, and the failure surfaces as a connection error rather
than as anything mentioning the key, so the docs must name it as the thing to back up.

Nothing else may be encrypted under this key without a version marker in the stored value. Adding a
second use later and changing the key derivation would silently fail to decrypt the first.

An operator who wants a real boundary supplies credentials the server cannot exfiltrate usefully —
that is, a database user restricted to what the Datasource is for (ADR-0003) — rather than expecting
this to protect a superuser password.
