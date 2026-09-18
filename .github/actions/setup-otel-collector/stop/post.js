// Best-effort shutdown: flush and stop the background Collector.
// Never fails the job; failures surface as warnings only.
const { execFileSync } = require('node:child_process');
const path = require('node:path');

try {
  execFileSync('bash', [path.join(__dirname, '..', 'scripts', 'stop-collector.sh')], {
    stdio: 'inherit',
  });
} catch (error) {
  console.log(`::warning::Failed to stop the OpenTelemetry Collector: ${error.message}`);
}
