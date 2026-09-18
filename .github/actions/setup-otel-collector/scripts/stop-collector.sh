#!/usr/bin/env bash
# Best-effort Collector shutdown: flush pending data, then stop. Never fails the job.
set -u

install_dir="${RUNNER_TEMP:?RUNNER_TEMP is required}/otelcol"
pid_file="$install_dir/collector.pid"
log_file="$install_dir/collector.log"

if [[ ! -f "$pid_file" ]]; then
  exit 0
fi
pid="$(cat "$pid_file")"
if ! kill -0 "$pid" 2>/dev/null; then
  rm -f "$pid_file"
  exit 0
fi

# spanmetrics does not flush on shutdown, so wait past metrics_flush_interval first.
sleep 8
kill -TERM "$pid" 2>/dev/null || true
for _ in $(seq 1 20); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.5
done
if kill -0 "$pid" 2>/dev/null; then
  echo '::warning::OpenTelemetry Collector did not stop in time; leaving it running.'
else
  rm -f "$pid_file"
fi

echo '--- OpenTelemetry Collector log (tail) ---'
tail -n 50 "$log_file" 2>/dev/null || true
exit 0
