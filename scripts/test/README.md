# Test Scripts

This directory contains shell scripts for testing various aspects of the OpenDataWorks platform, primarily focusing on workflow execution and lifecycle management.

## Scripts

- `test-workflow-lifecycle.sh`: comprehensive test of the workflow lifecycle (create, update, publish, execute, delete).
- `run-workflow-test.sh`: Helper script to run workflow tests.
- `check-all-workflows.sh`: Checks the status of all registered workflows.
- `find-actual-workflow.sh`: Helper to find workflow definitions.
- `verify-deletion.sh`: Verifies that a workflow has been correctly deleted.

## Usage

Run these scripts from the project root or within the `scripts/test` directory.

Example:
```bash
# Run the full lifecycle test
./scripts/test/test-workflow-lifecycle.sh
```
