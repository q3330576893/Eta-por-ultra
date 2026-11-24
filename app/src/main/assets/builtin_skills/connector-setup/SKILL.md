---
name: connector-setup
description: Configure third-party ecosystem connectors (MCP servers) safely. Use when the user wants to connect Notion, Linear, GitHub, GitLab, Sentry, Supabase, Slack, Feishu, Zapier, n8n or any custom MCP endpoint, or when an existing connector stops returning tools.
---

# Connector Setup

Use this skill when the task is connecting or repairing an external service through MCP.

## Procedure

1. Identify the target service and confirm it actually exposes an MCP or compatible HTTP endpoint. Do not invent an endpoint; if the catalog entry has an empty endpoint, the user must supply the real one from that service's documentation.
2. In the app, open the MCP settings page and pick the matching template. The template fills the name and endpoint and shows the service's availability conditions.
3. Ask the user for their own token. Never guess, reuse or hardcode a credential, and never write a token into a skill, note or log.
4. Save and let the app discover tools. Confirm the tool count and that only the tools the user enabled are active.
5. If discovery fails, report the exact error code: a 401 means the token is wrong or expired; a 404 means the endpoint path is wrong; a timeout means the network or the service is unreachable. Do not retry blindly with a different token.

## Boundaries

- Read-only tools first. Only enable write or destructive tools when the user explicitly asks for that action.
- A connector gives the model the same permissions its token carries. State that plainly instead of implying the app narrows it.
- Never enable a connector that the user did not ask for, and never silently add one during another task.
