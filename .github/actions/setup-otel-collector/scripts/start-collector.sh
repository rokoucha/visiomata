#!/usr/bin/env bash
# Start a local otelcol-contrib relay for Gradle build traces.
# Skips silently (exit 0) when there is no API key, e.g. on fork PRs.
set -euo pipefail

api_key="${OTEL_COLLECTOR_API_KEY:-}"
if [[ -z "$api_key" ]]; then
  echo 'No OTLP backend API key; skipping OpenTelemetry Collector startup.'
  exit 0
fi

version="${OTEL_COLLECTOR_VERSION:?OTEL_COLLECTOR_VERSION is required}"
install_dir="${RUNNER_TEMP:?RUNNER_TEMP is required}/otelcol"
mkdir -p "$install_dir"
bin="$install_dir/otelcol-contrib"
pid_file="$install_dir/collector.pid"
log_file="$install_dir/collector.log"

case "${RUNNER_ARCH:?RUNNER_ARCH is required}" in
  X64) arch='amd64' ;;
  ARM64) arch='arm64' ;;
  *)
    echo "::warning::Unsupported runner architecture for otelcol-contrib: $RUNNER_ARCH; continuing without build traces."
    exit 0
    ;;
esac

base="otelcol-contrib_${version#v}_linux_${arch}"
tarball="$install_dir/$base.tar.gz"
checksum_file="$tarball.sha256"
base_url="https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/$version"

# The per-asset .sha256 file holds a bare hash, so build the check line ourselves.
verify_tarball() {
  (cd "$install_dir" && echo "$(cat "$checksum_file")  $(basename "$tarball")" | sha256sum -c -)
}
if [[ -f "$tarball" && -f "$checksum_file" ]] && verify_tarball; then
  echo "Reusing cached otelcol-contrib $version ($arch)."
else
  echo "Downloading otelcol-contrib $version ($arch)."
  curl -fsSL --retry 3 -o "$tarball" "$base_url/$base.tar.gz"
  curl -fsSL --retry 3 -o "$checksum_file" "$base_url/$base.tar.gz.sha256"
  verify_tarball
fi
tar -xzf "$tarball" -C "$install_dir" otelcol-contrib
chmod +x "$bin"

# The api-key input carries the OTEL_EXPORTER_OTLP_HEADERS secret
# ("Authorization=Bearer <token>"); a bare token is also accepted.
token="${api_key#Authorization=Bearer }"
token="${token#Bearer }"
token="$(printf '%s' "$token" | tr -d '[:space:]')"
if [[ -z "$token" ]]; then
  echo '::warning::Empty OTLP backend API key; continuing without build traces.'
  exit 0
fi
echo "::add-mask::$token"
export OTEL_BACKEND_API_KEY="$token"

nohup "$bin" --config "$GITHUB_ACTION_PATH/config.yaml" >"$log_file" 2>&1 &
echo -n "$!" >"$pid_file"

ready='false'
for _ in $(seq 1 20); do
  if code="$(curl -s -o /dev/null -w '%{http_code}' 'http://127.0.0.1:4318/' 2>/dev/null)" && [[ -n "$code" ]]; then
    ready='true'
    break
  fi
  sleep 0.5
done

if [[ "$ready" != 'true' ]]; then
  echo '::warning::OpenTelemetry Collector failed to start within 10s; continuing without build traces.'
  echo "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=''" >>"$GITHUB_ENV"
  tail -n 50 "$log_file" || true
  exit 0
fi

echo "OpenTelemetry Collector started (otelcol-contrib $version, pid $(cat "$pid_file"))."
