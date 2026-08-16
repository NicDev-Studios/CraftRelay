# Security Policy

## Supported versions

Security fixes are provided for the latest released preview line. After a newer minor preview is published, older preview lines no longer receive fixes unless a release notice explicitly says otherwise.

| Version | Status |
| --- | --- |
| `0.1.x` | Supported after the first `0.1.0` release |
| Unreleased builds | Best effort only |
| Older preview lines | Not supported |

## Report a vulnerability

Do not open a public issue for a suspected vulnerability. Email **security@nicdevtv.de** with:

- the affected CraftRelay version and platform;
- a concise description of the impact;
- reproducible steps or a minimal proof of concept;
- any conditions required for exploitation;
- suggested mitigations, if known.

Remove credentials, Redis URLs, access tokens, player data, payload contents, lease tokens, session IDs, and correlation IDs from logs before attaching them. If sensitive material is required to reproduce the issue, mention that first so a safer transfer method can be arranged.

You should receive an acknowledgement within three business days and an initial assessment within seven business days. Complex reports may take longer to resolve, but material status changes will be communicated to the reporter.

Please allow a reasonable remediation period before public disclosure. Credit is given in the security advisory unless the reporter prefers to remain anonymous.

## Security-sensitive areas

Reports are especially useful when they involve:

- bypassing Redis authentication, TLS, lease fencing, or duplicate-session protection;
- deserializing an unregistered or attacker-selected Java class;
- leaking credentials or internal message payloads through logs or artifacts;
- escaping configured message-size, queue, listener, handler, or request limits;
- executing Bukkit, Velocity, Netty, or Lettuce work on an unsafe or blocked thread;
- publishing tampered release artifacts or bypassing release verification.

Gameplay bugs, configuration questions, and ordinary crashes without a security impact belong in the public issue tracker.
