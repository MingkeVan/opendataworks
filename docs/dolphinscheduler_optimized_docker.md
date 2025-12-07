
# Optimized DolphinScheduler Docker Start Command (Podman)

For testing environments with limited resources, use the following `podman` command to start DolphinScheduler Standalone Server (v3.2.0).

**Critical Update:** We use `JAVA_OPTS` with `-D` system properties to force-disable resource load protection logic. This bypasses the ambiguous environment variable mapping and ensures the server starts even with <1% available RAM.

```bash
export DOLPHINSCHEDULER_VERSION=3.2.0

podman run --name dolphinscheduler-standalone-server \
    -p 12345:12345 -p 25333:25333 \
    -d \
    -e JAVA_OPTS="-Xms512m -Xmx1g -Dmaster.server-load-protection.enabled=false -Dworker.server-load-protection.enabled=false -Dalert.server-load-protection.enabled=false -Dmaster.reserved.memory=0.0 -Dworker.reserved.memory=0.0" \
    -e MASTER_EXEC_THREADS=2 \
    -e WORKER_EXEC_THREADS=2 \
    apache/dolphinscheduler-standalone-server:"${DOLPHINSCHEDULER_VERSION}"
```

## Environment Variables Explained

*   `JAVA_OPTS`: Passes Java system properties directly to the application.
    *   `-D...server-load-protection.enabled=false`: Completely disables the CPU/Memory overload checks. Use this ONLY for testing environments where stability is secondary to running on low specs.
    *   `-D...reserved.memory=0.0`: Tells the server not to reserve any memory buffer.
    *   `-Xms512m -Xmx1g`: Limits JVM Heap to 1GB.
*   `MASTER_EXEC_THREADS=2` / `WORKER_EXEC_THREADS=2`: Reduces execution concurrency to save CPU/Memory.

## Troubleshooting

If using `docker` instead of `podman`, simply replace `podman` with `docker` in the command above.
