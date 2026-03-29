---
name: cloudflare-allowlist-pipeline
description: "Maintain the Cloudflare allowlist Jenkins pipeline. Use when working on declarative-pipeline.groovy, Cloudflare WAF allowlist updates, retry and timeout logic, IP change detection, or Jenkins credential handling for Cloudflare API access."
argument-hint: "Describe the Cloudflare allowlist behavior or pipeline change you want."
user-invocable: true
---

# Cloudflare Allowlist Pipeline

## When to Use

- Modify Cloudflare allowlist update behavior
- Adjust checkout retry, timeout, or concurrency control
- Change IP detection or state-file logic
- Update Cloudflare credential usage

## Procedure

1. Review [declarative-pipeline.groovy](../../../src/declarative-pipeline.groovy) before changes.
2. Keep checkout resilient with retries, timeouts, and `disableConcurrentBuilds` where relevant.
3. Verify Cloudflare token access before mutating rules.
4. Preserve idempotent behavior: detect current state, then update only what changed.
5. Keep credentials in Jenkins secret storage or equivalent injected environment variables.
