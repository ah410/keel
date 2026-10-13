# Security policy

Keel holds credentials for a NAS, so security reports are taken seriously.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub's
[private vulnerability reporting](https://github.com/ah410/keel/security/advisories/new) rather
than in a public issue. Include the app version, your Android version, and steps to reproduce.
You should get a reply within a week. Once a fix is released, the advisory is published with credit
to you unless you'd rather stay anonymous.

Vulnerabilities in TrueNAS itself should go to iXsystems, not here.

## Supported versions

Only the latest release gets security fixes.

## Scope

In scope: anything that lets someone other than you read the stored key material, bypass the
device unlock, perform server actions without the confirmation the app promises, connect to a
server whose certificate wasn't approved, or leak server data off the phone.
