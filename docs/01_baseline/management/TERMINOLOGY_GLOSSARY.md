# Project Terminology Glossary

> **Status**: `Draft` (Review Required)
> **Last Updated**: 2026-11-17

This glossary defines the standard terminology to be used across all technical documentation, design specifications, and code identifiers to ensure consistency and prevent ambiguity.

## 1. Core Domain Entities

| Term | Canonical Form | Description | Notes |
| :--- | :--- | :--- | :--- |
| **Tenant** | `Tenant` | A logical isolation unit within the platform. All data and operations are scoped to a `Tenant`. | Use `tenant_id` in code/DB. |
| **Agent** | `Agent` | An autonomous or semi-autonomous unit of intelligence that can perform tasks, use tools, and interact with users. | Distinction: `Leaf Agent` vs `Orchestration Agent`. |
| **Tool** | `Tool` | An external capability or function that an `Agent` can invoke (e.g., Search, Calculator, API Call). | Defined by a `Tool Schema`. |
| **Run** | `Run` | A single execution instance of an `Agent` or a sequence of agent calls. | Contains `trace_id`, `input`, `output`, and `status`. |
| **Skill** | `Skill` | A higher-level capability or a bundle of tools and logic that an Agent uses to achieve a goal. | Often wraps specific `Tools`. |

## 2. Security & Access Control

| Term | Canonical Form | Description | Notes |
| :--- | :--- | :--- | :--- |
| **RBAC** | `RBAC` | Role-Based Access Control. | Standard model for user permissions. |
| **RLS** | `RLS` | Row-Level Security. | The primary mechanism for multi-tenant data isolation at the database layer. |
| **Policy** | `Policy` | A set of rules governing access to resources. | Applies to `Agents`, `Tools`, and `Data`. |
| **Authz** | `Authz` | Authorization process. | Used to distinguish from `Authn` (Authentication). |
| **Fencing Token** | `Fencing Token` | A mechanism to prevent "zombie" or delayed requests from causing side effects (e.g., in distributed locks or outdated runs). | Critical for idempotency. |

## 3. RAG & Intelligence

| Term | Canonical Form | Description | Notes |
| :--- | :--- | :--- | :--- |
| **Corpus** | `Corpus` | A collection of documents/data used for retrieval. | Scoped to a `Tenant`. |
| **Chunk** | `Chunk` | A segment of a document used for embedding and retrieval. | Defined by `Chunking Strategy`. |
| **Embedding** | `Embedding` | A vector representation of a piece of text/data. | |
| **Retrieval** | `Retrieval` | The process of finding relevant `Chunks` or `Context` for a given query. | |

## 4. System Infrastructure

| Term | Canonical Form | Description | Notes |
| :--- | :--- | :--- | :--- |
| **Outbox** | `Outbox` | A messaging pattern used to ensure atomicity between database changes and event publishing. | Guarantees "At-least-once" delivery. |
| **SSE** | `SSE` | Server-Sent Events. | The protocol used for real-time streaming of agent responses and events. |
| **MCP** | `MCP` | Model Context Protocol. | The standard for connecting Agents to external tools and data sources. |

---
*Reviewers: [TODO: Assign to Product/Lead Engineer]*
