[CmdletBinding()]
param(
    [Parameter()]
    [string]$ProjectPath = 'D:\mywork\monitor',

    [Parameter()]
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path
$codexHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex'
$statePath = Join-Path $codexHome '.codex-global-state.json'
$database = Get-ChildItem -LiteralPath $codexHome -Filter 'state_*.sqlite' -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
    throw "Codex state file was not found: $statePath"
}

if ($null -eq $database) {
    throw "No Codex state database was found under: $codexHome"
}

if ($Apply) {
    $runningCodexProcesses = Get-Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ProcessName -match '^(ChatGPT|codex|codex-code-mode-host)$' -and
            $_.Path -like '*OpenAI.Codex*'
        }

    if ($runningCodexProcesses) {
        $processSummary = ($runningCodexProcesses |
            Select-Object ProcessName, Id |
            Format-Table -AutoSize |
            Out-String).Trim()
        throw "Codex is still running. Fully exit Codex before applying the repair.`n$processSummary"
    }
}

$pythonCode = @'
import argparse
import datetime as dt
import json
import os
import shutil
import sqlite3
import tempfile


def normalize_path(value):
    if not value:
        return ""
    if value.startswith("\\\\?\\"):
        value = value[4:]
    return os.path.normcase(os.path.abspath(value.rstrip("\\/")))


parser = argparse.ArgumentParser()
parser.add_argument("--state", required=True)
parser.add_argument("--database", required=True)
parser.add_argument("--project", required=True)
parser.add_argument("--apply", action="store_true")
args = parser.parse_args()

with open(args.state, "r", encoding="utf-8") as handle:
    state = json.load(handle)

target_path = normalize_path(args.project)
matches = []
for project_id, project in state.get("local-projects", {}).items():
    roots = project.get("rootPaths", [])
    if any(normalize_path(root) == target_path for root in roots):
        matches.append((project_id, project))

if len(matches) != 1:
    raise SystemExit(
        f"Expected exactly one saved local project for {args.project!r}; found {len(matches)}"
    )

project_id, project = matches[0]
connection = sqlite3.connect(f"file:{args.database}?mode=ro", uri=True)
try:
    rows = connection.execute("SELECT id, cwd FROM threads").fetchall()
finally:
    connection.close()

thread_ids = sorted(
    thread_id for thread_id, cwd in rows if normalize_path(cwd) == target_path
)
if not thread_ids:
    raise SystemExit(f"No stored Codex threads use cwd {args.project!r}")

assignments = state.setdefault("thread-project-assignments", {})
projectless = state.setdefault("projectless-thread-ids", [])
root_hints = state.setdefault("thread-workspace-root-hints", {})

already_assigned = 0
needs_assignment = []
for thread_id in thread_ids:
    assignment = assignments.get(thread_id)
    if assignment and assignment.get("projectId") == project_id:
        already_assigned += 1
    else:
        needs_assignment.append(thread_id)

projectless_matches = sorted(set(thread_ids).intersection(projectless))

result = {
    "mode": "apply" if args.apply else "preview",
    "projectPath": args.project,
    "projectId": project_id,
    "storedThreads": len(thread_ids),
    "alreadyAssigned": already_assigned,
    "assignmentsToRepair": len(needs_assignment),
    "projectlessEntriesToRemove": len(projectless_matches),
    "backupPath": None,
    "verifiedAssigned": None,
    "verifiedProjectlessEntries": None,
}

if args.apply:
    for thread_id in thread_ids:
        assignments[thread_id] = {
            "projectKind": "local",
            "projectId": project_id,
            "cwd": args.project,
            "pendingCoreUpdate": False,
        }
        root_hints[thread_id] = args.project

    thread_id_set = set(thread_ids)
    state["projectless-thread-ids"] = [
        thread_id for thread_id in projectless if thread_id not in thread_id_set
    ]

    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    backup_path = f"{args.state}.thread-project-repair-{timestamp}.bak"
    shutil.copy2(args.state, backup_path)

    state_dir = os.path.dirname(args.state)
    descriptor, temporary_path = tempfile.mkstemp(
        prefix=".codex-global-state.repair-", suffix=".json", dir=state_dir
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as handle:
            json.dump(state, handle, ensure_ascii=False, separators=(",", ":"))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, args.state)
    except Exception:
        if os.path.exists(temporary_path):
            os.unlink(temporary_path)
        raise

    result["backupPath"] = backup_path
    with open(args.state, "r", encoding="utf-8") as handle:
        verified_state = json.load(handle)
    verified_assignments = verified_state.get("thread-project-assignments", {})
    verified_projectless = set(verified_state.get("projectless-thread-ids", []))
    result["verifiedAssigned"] = sum(
        1
        for thread_id in thread_ids
        if verified_assignments.get(thread_id, {}).get("projectId") == project_id
    )
    result["verifiedProjectlessEntries"] = len(
        set(thread_ids).intersection(verified_projectless)
    )
    if result["verifiedAssigned"] != len(thread_ids):
        raise SystemExit("Post-write verification failed: not every thread is assigned")
    if result["verifiedProjectlessEntries"] != 0:
        raise SystemExit("Post-write verification failed: projectless entries remain")

print(json.dumps(result, ensure_ascii=False, indent=2))
'@

$arguments = @(
    '-c', $pythonCode,
    '--state', $statePath,
    '--database', $database.FullName,
    '--project', $resolvedProjectPath
)
if ($Apply) {
    $arguments += '--apply'
}

& python @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Repair helper exited with code $LASTEXITCODE"
}
