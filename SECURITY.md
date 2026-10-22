# Security policy

## Reporting a vulnerability

Please don't open a public issue for a security problem.

Report it through GitHub private vulnerability reporting: use **Report a vulnerability** under this
repository's **Security** tab.

Include what you found, the steps to reproduce it, which host or version it affects, and what you think the
impact is. Don't include real user data.

## What to expect

- We acknowledge your report within **3 business days** and give you an assessment within **10**.
- Fix targets by severity: critical within 7 days, high within 30, others in the next release.
- We credit you if you want that. There is no bounty.

## Safe harbour

Good-faith testing on your own account and your own data is welcome. Don't access other people's data, and
stop and report as soon as you reach it.

## Scope

**In scope:** the API, the dashboard, the Pivot plugin, and this repository's code (the jar on customers' servers; note it's public).

**Out of scope:** volumetric denial of service, social engineering, third-party services (Stripe, Discord,
GitHub, Vercel, Railway, Cloudflare, Resend), missing headers without a demonstrated impact, self-XSS, and
rate-limit reports without a demonstrated impact.

## Supported versions

Latest minor (`0.2.x`).

## Rules for contributors and agents

These extend the invariants in `CLAUDE.md`.

- Every route declares a policy.
- Ownership failures return 404, not 403.
- Tiers are enforced server-side.
- Never log keys, tokens or player IPs.
- No secret fallbacks in code.
- No secrets in `NEXT_PUBLIC_*`.
- Audit events go through `audit.record` only.
- Don't add lines to `policy_unmigrated.txt`.
- New user-owned tables join `delete_account`.
- Plugin invariants (from `CLAUDE.md`): Java 8 target, async HTTP, HTTPS-only, no custom TrustManager, never player IPs.
