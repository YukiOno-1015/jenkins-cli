---
name: macos-jenkins-agent-launchd
description: "Manage the macOS launchd setup for the Jenkins agent. Use when working on launchd plist templates, automatic Jenkins agent startup on macOS, KeepAlive configuration, machost agent recovery, or launchd documentation for local Jenkins agents."
argument-hint: "Describe the launchd or macOS Jenkins agent change you want."
user-invocable: true
---

# macOS Jenkins Agent launchd

## When to Use

- Update the launchd plist template for the Jenkins agent
- Document macOS Jenkins agent startup and recovery
- Adjust KeepAlive, RunAtLoad, log paths, or environment variables

## Procedure

1. Review [README.md](../../../README.md) and the launchd template under `docs/templates` before editing.
2. Keep plist paths absolute.
3. Preserve auto-restart behavior through `KeepAlive` and `RunAtLoad` when appropriate.
4. Document any required local path substitution clearly.
