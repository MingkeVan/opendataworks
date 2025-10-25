# Task Execution Workflow Lifecycle - Issue Analysis & Fix

## Issue Summary

When executing individual tasks using the "Execute Task" feature, the system was creating temporary DolphinScheduler workflows but **failing to delete them**, causing workflow accumulation over time.

## Root Cause Analysis

### What Happens During Task Execution

When `DataTaskService.executeTask(taskId)` is called (DataTaskService.java:406-513):

1. **Line 425**: Creates a temporary workflow name: `test-task-{taskCode}`
2. **Line 463-469**: Calls `syncWorkflow(0L, ...)` which creates a **NEW workflow** in DolphinScheduler
3. **Line 472**: Sets the workflow to ONLINE state
4. **Line 483**: Starts the workflow execution
5. **Line 492**: Schedules async cleanup via `cleanupTempWorkflowAsync()` after 5 minutes

### The Problem

The `cleanupTempWorkflowAsync()` method calls `dolphinSchedulerService.deleteWorkflow()`, which delegates to the Python service's `delete_workflow()` method (scheduler.py:541-587).

**However**, the original implementation only removed the workflow from cache:

```python
# Remove from cache if present
if workflow_code in self._workflow_cache:
    del self._workflow_cache[workflow_code]
    # But doesn't actually delete from DolphinScheduler!
```

The comment even admitted: `"Since pydolphinscheduler SDK doesn't provide delete_workflow API, we simply remove from cache for now."`

This meant temporary workflows remained in DolphinScheduler indefinitely, accumulating with each task execution.

## Solution Implemented

### Enhanced delete_workflow() Method

Modified `dolphinscheduler-service/dolphinscheduler_service/scheduler.py:541-667` to:

1. **Authenticate with DolphinScheduler API**
   - Login via REST API to get session token
   - Uses credentials from configuration

2. **Query Project Code**
   - Uses java_gateway to get project code by name
   - Required for constructing the delete endpoint URL

3. **Call DolphinScheduler REST API**
   - DELETE `/api/v1/projects/{projectCode}/process-definition/{workflowCode}`
   - Includes authentication token in request

4. **Remove from Cache**
   - Still removes from local cache regardless of API success
   - Ensures cleanup even if API call fails

### Key Changes

```python
# Use DolphinScheduler REST API to delete workflow
import requests

# Login to get token
login_response = requests.post(
    f"{api_base}/login",
    data={
        "userName": self.settings.user_name,
        "userPassword": self.settings.user_password
    }
)

token = response_data.get("data", {}).get("sessionId")

# Delete the workflow
delete_url = f"{api_base}/projects/{project_code}/process-definition/{workflow_code}"
delete_response = requests.delete(delete_url, params={"token": token})
```

## Verification

### Integration Test Created

`backend/src/test/java/com/onedata/portal/service/TaskExecutionWorkflowTest.java`

**Test 1: `testTaskExecutionCreatesAndDeletesTempWorkflow()`**
- Creates a test task
- Executes it (triggering temp workflow creation)
- Verifies workflow exists in DolphinScheduler
- Manually triggers cleanup (instead of waiting 5 minutes)
- Verifies workflow is deleted from DolphinScheduler

**Test 2: `testMultipleTaskExecutionsHandleWorkflowsProperly()`**
- Executes 3 tasks simultaneously
- Verifies 3 temporary workflows are created
- Cleans up all temporary workflows
- Verifies all are deleted from DolphinScheduler

### Running the Tests

```bash
# Run the integration test
cd backend
./gradlew test --tests TaskExecutionWorkflowTest

# Or run all tests
./gradlew test
```

## API Confirmation Methods

### Manual Verification via DolphinScheduler API

1. **List all workflows before execution:**
```bash
curl "http://localhost:12345/dolphinscheduler/projects/{projectCode}/process-definition/list?token={token}"
```

2. **Execute a task** via the application

3. **List workflows again** - should see new workflow with name `test-task-{code}`

4. **Wait 5 minutes** (or manually trigger cleanup)

5. **List workflows again** - temporary workflow should be gone

### Via DolphinScheduler Web UI

1. Navigate to: `http://localhost:12345/dolphinscheduler`
2. Login with admin credentials
3. Go to Project → Workflow Definitions
4. Before task execution: Note workflow count
5. Execute task: See new workflow appear (name starts with `test-task-`)
6. After cleanup: Verify workflow is deleted

## Impact

### Before Fix
- ✗ Temporary workflows accumulated indefinitely
- ✗ Manual cleanup required via DolphinScheduler UI
- ✗ Database pollution with unused workflow definitions
- ✗ Potential performance degradation over time

### After Fix
- ✓ Temporary workflows are properly deleted after 5 minutes
- ✓ No manual intervention needed
- ✓ Clean DolphinScheduler database
- ✓ Sustainable long-term operation

## Configuration

The deletion functionality uses existing configuration from `dolphinscheduler-service/dolphinscheduler_service/config.py`:

```python
api_base_url: str = Field(
    default="http://localhost:12345/dolphinscheduler",
    alias="DS_API_BASE_URL"
)
user_name: str = Field(default="admin", alias="PYDS_USER_NAME")
user_password: str = Field(
    default="dolphinscheduler123",
    alias="PYDS_USER_PASSWORD"
)
```

Ensure these environment variables are properly set in your deployment.

## Future Improvements

1. **Consider Alternative Approaches:**
   - Option A: Don't create temporary workflows - use DolphinScheduler's "run single task" feature if available
   - Option B: Reuse a single "test workflow" and just trigger different instances
   - Option C: Implement workflow pooling with cleanup on shutdown

2. **Add Metrics:**
   - Track number of temporary workflows created
   - Track deletion success/failure rate
   - Alert if cleanup fails repeatedly

3. **Configurable Cleanup Delay:**
   - Make the 5-minute delay configurable
   - Allow immediate cleanup for short-running tasks

4. **Retry Logic:**
   - Add retry mechanism if deletion fails
   - Implement exponential backoff

## Related Files

- `backend/src/main/java/com/onedata/portal/service/DataTaskService.java` (lines 406-513)
- `dolphinscheduler-service/dolphinscheduler_service/scheduler.py` (lines 541-667)
- `backend/src/test/java/com/onedata/portal/service/TaskExecutionWorkflowTest.java`
- `dolphinscheduler-service/dolphinscheduler_service/config.py`

## Conclusion

The fix ensures that temporary workflows created during task execution are properly deleted from DolphinScheduler, preventing workflow accumulation and maintaining a clean system state.
