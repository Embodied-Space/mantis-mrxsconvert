# 05 — Temp Hygiene

**Depends on:** nothing. **Decisions:** C1, C2 (orphan cleanup), D6.

## Purpose

Create, own, and reliably remove intermediate Zarr workspaces in system temp — including after crashes.

## Interface (sketch)

```java
TempWorkspace TempWorkspace.create();     // %TEMP%\mantis-slideconvert\<uuid>\
void          TempWorkspace.close();      // recursive delete, retries for Windows file locks
int           TempWorkspace.cleanupOrphans(); // called once at extension startup, off the FX thread
```

## Design

- Root: `%TEMP%\mantis-slideconvert\`.
- Each workspace contains `owner.lock` holding `{pid, processStartTime}` and is held with an exclusive `FileLock` for its lifetime.
- **Orphan rule:** a workspace is an orphan iff its lock can be acquired (owner dead) **or** `owner.lock` is missing and dir mtime > 24 h.
  PID alone is not trusted (PID reuse); the OS file lock is the authority.
- Deletion retries a few times with backoff (Windows AV / indexer handles); failures are logged, never thrown to UI.

## Out of scope

Disk-space estimation (07). Output `.partial` files (03).

## Acceptance

- Two QuPath instances: instance B's startup cleanup never deletes A's live workspace.
- Kill QuPath mid-conversion → next startup removes the leftover workspace.
- Cleanup of a dir with a file held open logs a warning and does not crash.
