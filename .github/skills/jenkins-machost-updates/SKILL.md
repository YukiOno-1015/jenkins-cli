---
name: jenkins-machost-updates
description: "Manage Jenkins machost update pipelines. Use when working on update-machosts.groovy, apt update automation, SSH-based server updates, root login host handling, NodeSource repository disabling, or scheduled Jenkins maintenance jobs for machost servers."
argument-hint: "Describe the machost maintenance or pipeline change you want to make."
user-invocable: true
---

# Jenkins Machost Updates

## When to Use

- Modify the machost auto-update pipeline
- Add or remove managed hosts
- Change root-login host handling
- Fix apt repository issues such as NodeSource signing failures
- Adjust cron scheduling or Jenkins execution behavior for machost maintenance

## Procedure

1. Review [update-machosts.groovy](../../../src/update-machosts.groovy) to confirm host groups and update flow.
2. Keep host lists explicit and grouped by role.
3. Prefer simple SSH execution paths: root login hosts separately, non-root hosts via `sudo -n`.
4. When repository-specific failures occur, add narrow host-scoped mitigations instead of global workarounds.
5. Preserve fail-per-host behavior so one host failure does not hide the rest of the run.

## Notes

- This repository runs these maintenance pipelines on the `machost` Jenkins agent.
- Existing practice is to keep host-specific exceptions small and explicit.
