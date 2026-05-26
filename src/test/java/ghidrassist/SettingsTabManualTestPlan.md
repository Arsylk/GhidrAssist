# Manual Test Plan: `SettingsTab.java`

## Overview
This test plan covers manual verification of the modifications applied to error handling in `onTestProvider()` and `onTestSymGraph()` methods. Changes focus on improved exception resilience, detailed error logging, and user feedback refinement.

## Related Features
- `SettingsTab` API provider testing (LLM connections)
- SymGraph API connectivity validation

## Test Cases

### 1. Test Connection - Valid API
- **Steps**:
  1. Open the GhidrAssist `SettingsTab`.
  2. Select a valid API provider.
  3. Click `Test`.
- **Expected**:
  - Status: "Testing..."
  - Success icon and tooltip: "Connection successful."
  - Console log: `ghidra.util.Msg.info` confirming success.

### 2. Test Connection - Invalid API
- **Steps**:
  1. Select a provider with an invalid configuration.
  2. Click `Test`.
- **Expected**:
  - Status: "Connection failed: <Detailed error>"
  - Error icon with tooltip.
  - Console log: Stack trace with `RuntimeException(errorMessage)`.

### 3. Test Connection - Cancel Button
- **Steps**:
  1. Select a valid provider.
  2. Click `Test`, then `Cancel` immediately.
- **Expected**:
  - Status: "Test canceled."
  - Tooltip reflects user-initiated cancellation.

### 4. SymGraph API - Authentication Success
- **Steps**:
  1. Configure a correct SymGraph API key.
  2. Click `Test`.
- **Expected**:
  - Success response: "API reachable, authentication successful."
  - `successIcon` visible.

### 5. SymGraph API - Authentication Failure
- **Steps**:
  1. Configure an incorrect SymGraph API key.
  2. Click `Test`.
- **Expected**:
  - Status: "API reachable but authentication failed: <Reason>."
  - Console log captures detailed failure reason.

### 6. Timeout Handling
- **Steps**:
  1. Configure a slow provider URL.
  2. Attempt `Test`.
- **Expected**:
  - Tooltip: "Test timed out after 15 seconds."
  - Error stack trace logged.

## Notes
- Ensure the updated `SettingsTab` exception handlers are invoked consistently.
- Verify all `SwingWorker` transitions reset UI elements properly (buttons, status labels).