---
name: proxmox-host-maintenance
description: "Monitor and update Proxmox hosts from Jenkins. Use when working on update-proxmox-hosts.groovy, Proxmox package inspection, quorum-aware updates, Slack or Discord notifications, notification test mode, or Jenkins-triggered Proxmox maintenance workflows."
argument-hint: "Describe the Proxmox monitoring, notification, or update workflow you want to change."
user-invocable: true
---

# Proxmox Host Maintenance

## When to Use

- Add or modify Proxmox host monitoring
- Adjust `pveversion`, `pvecm`, or package inspection logic
- Change notification flow for Slack or Discord
- Add test-only notification behavior
- Implement safe update execution for Proxmox hosts

## Procedure

1. Inspect [update-proxmox-hosts.groovy](../../../src/update-proxmox-hosts.groovy) before editing.
2. Keep monitoring and update execution separate, with safe defaults.
3. Treat `TEST_NOTIFICATIONS_ONLY` as a no-op mode for infra changes that only need webhook validation.
4. For multi-node clusters, check quorum before allowing updates.
5. Use Jenkins credentials for webhook URLs instead of hardcoded values.
6. Avoid Jenkins sandbox issues by preferring simple Groovy and shell constructs over blocked APIs.

## Notes

- The Proxmox pipeline runs on the `machost` agent.
- Slack credential ID: `slack-webhook-url`
- Discord credential ID: `discord-webhook-url`
