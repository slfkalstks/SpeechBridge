#!/bin/sh
set -eu
cd "$(dirname "$0")"
node server/demo.mjs start
node scripts/demo-artifacts.mjs launch
echo '시연 준비 완료. Simulator에서 SpeechBridge를 확인하세요. 절차: docs/DEMO.md'
