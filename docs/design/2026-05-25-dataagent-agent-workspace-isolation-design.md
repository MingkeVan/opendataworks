# DataAgent Agent Workspace Isolation Design

## Current State

DataAgent profile execution already passes a managed `cwd` to Claude Agent SDK and generates enabled Skill symlinks under `<cwd>/.claude/skills`.

The current profile cwd layout is inconsistent:

- the default profile uses `HOME/.dataagent/runtime/enabled-skills`
- custom profiles use `HOME/.dataagent/runtime/agents/<agent_id>`

That `agents` directory name is easy to confuse with Claude subagents. More importantly, the SDK receives only `cwd`, allowed tool names, and permission mode. The runtime does not currently enforce that file-oriented tools stay inside the current profile cwd.

## Problem

Different intelligent-query agents should work in separate profile workspaces. During a run, an agent must not search upward into parent directories or inspect sibling profile state.

Relying only on Claude SDK `cwd` is not enough. Tools such as `Read`, `LS`, `Glob`, `Grep`, and `Bash` can receive relative paths, absolute paths, or commands that refer to `..` or another directory outside the run workspace.

## Scope

In scope:

- DataAgent managed workspace path resolution for agent profiles.
- SDK runtime options for enforcing tool filesystem boundaries.
- Regression tests for workspace paths and parent-directory denial.
- Documentation for the new runtime layout.

Out of scope:

- Database schema changes.
- Arbitrary administrator-supplied filesystem workspaces.
- Changing Skill storage or Skill editing APIs.
- Container-level sandboxing or OS namespace isolation.

## Solution

Use one workspace layout for every DataAgent profile:

```text
HOME/.dataagent/runtime/
  workspaces/
    agent_default/
      .claude/
        skills/
    agent_opendataworks/
      .claude/
        skills/
    <custom_agent_id>/
      .claude/
        skills/
```

The backend continues to expose `resolved_workdir` for API compatibility, but its value becomes the profile workspace path.

For each task:

1. Resolve the selected profile workspace from the profile `agent_id`.
2. Generate the enabled Skill symlinks under `<workspace>/.claude/skills`.
3. Pass the workspace as SDK `cwd`.
4. Register a `PreToolUse` runtime hook that denies filesystem escape attempts:
   - deny any `Read`, `LS`, `Glob`, or `Grep` path input containing a parent-directory segment
   - deny any resolved file path outside the current workspace and explicitly enabled Skill roots
   - deny `Bash` commands that include parent-directory path segments
   - deny absolute paths in `Bash` unless they are inside an allowed root, are the configured DataAgent Python executable, or are a read-only view of an offloaded tool-result file (see below)

Enabled Skill roots are allowed because the workspace exposes them through symlinks under `.claude/skills`; the hook still resolves symlinks and only allows roots for Skills enabled for the current profile.

The hook also makes one narrow, read-only exception for offloaded tool results. When a tool result exceeds the inline size limit, Claude Code writes the full result to `<config_dir>/projects/<encoded_cwd>/<session_id>/tool-results/<tool_use_id>.{txt,json}` (where `config_dir` is `$CLAUDE_CONFIG_DIR` or `$HOME/.claude`, and `encoded_cwd` is `realpath(cwd)` with every non-alphanumeric char replaced by `-`) and rewrites the inline result with a "Full output saved to: <path>" pointer; the CLI does not dictate which tool views it, so without an allowance the natural follow-up — either `Read`, or a plain `Bash` command such as `cat <path>` — is denied as "outside workspace". Rather than add the per-project data dir as a general allowed root (which would also expose session `.jsonl` transcripts, `subagents/` logs, and meta files to `Read`/`LS`/`Glob`/`Grep`/`Bash`), the hook allows only a `Read`, or a read-only `Bash` view, of a path that resolves exactly to `<config_dir>/projects/<encoded_cwd>/<session>/tool-results/<file>.{txt,json}`.

For `Bash` the allowance is deliberately read-only, because — unlike `Read` — a shell command can also delete, move, execute, or overwrite the file, which lives outside the workspace. The command is tokenized with a punctuation-aware lexer so operators (`>`, `>>`, `2>`, `|`, `;`, `&&`, subshells) are separated from adjacent paths even without whitespace; this closes the earlier gap where a glued redirect such as `cat <path>>/tmp/out` hid its target inside a single token and skipped validation. The tool-result exception is then granted to an absolute path only when it is a **non-redirect-target argument of a known read-only viewer command** (`cat`, `tac`, `head`, `tail`, `less`, `more`, `nl`, `wc`, `cut`, `grep`/`egrep`/`fgrep`, `od`, `strings`, `file`, `stat`). A redirect target, a command word (executing the file directly), or an argument of a mutating command (`rm`, `mv`, `cp`, `tee`, `python`, …) never receives the exception and falls through to the normal "outside workspace" denial, so the allowance cannot be used to copy, mutate, or execute the file or otherwise escape the workspace. The project root and its session-state files stay outside the boundary, `LS`/`Glob`/`Grep` get no exception, and the encoded-cwd scoping keeps it to this run only — never another topic's data or shared credentials, even when `HOME` is shared across topics in the default in-process mode.

## Interfaces

No public API shape changes are required.

- `resolved_workdir` remains in agent profile responses.
- The visible path changes from `.../runtime/agents/<agent_id>` to `.../runtime/workspaces/<agent_id>`.
- Runtime logs continue to report the SDK `cwd`.

## Tradeoffs

The SDK session project path changes for profile runs because `cwd` changes. Existing historical topics still keep their DataAgent topic/task records, but Claude SDK local resume files tied to the old cwd path may not be reused.

The runtime hook is an application-level guard, not a container sandbox. It prevents normal SDK tool calls from escaping the workspace, but it does not replace OS-level hardening. Container deployments should still run the DataAgent backend as a non-root user with a narrow writable home volume.

The Bash validation is intentionally conservative for parent-directory segments and absolute paths. This matches the intelligent-query contract, where platform scripts must be invoked through `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...` instead of probing arbitrary filesystem locations.

## Verification

- Unit tests for profile workspace path resolution.
- Unit tests for file tool parent-directory and outside-root denial.
- Unit tests for allowed workspace and enabled Skill access.
- Unit tests for the offloaded tool-result allowance: `Read` and read-only `Bash` views are allowed, while mutating/executing `Bash` commands and redirect targets (including glued no-space redirect forms) are denied.
- Task executor test proving SDK options include workspace boundary hooks.
- Targeted pytest for `test_agent_profile_service.py`, `test_agent_runtime.py`, and `test_task_executor.py`.

