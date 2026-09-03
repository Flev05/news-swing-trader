# AI Worker Scaffold

The deterministic fake inference seam from Milestone 0 remains unchanged. Milestone 1 adds
only a JSON Schema validator used to verify the authoritative shared contract corpus. The
worker still performs no LLM inference, financial extraction, database work, external request,
or model download.

Run after repository bootstrap:

```bash
../../.tools/uv/0.12.9/uv run --directory . news-swing-ai-worker "sample input"
```
