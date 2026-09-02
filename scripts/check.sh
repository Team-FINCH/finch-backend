#!/usr/bin/env bash
# 백엔드 파트 검사. CI 와 로컬이 같은 목록을 쓰도록 여기 한 곳에만 정의한다.
# 사용법: backend/scripts/check.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# Testcontainers 가 Docker 를 요구한다. 없으면 gradle 스택트레이스 전에 알려주고 끝낸다.
if ! docker info >/dev/null 2>&1; then
  echo "✗ Docker 에 연결할 수 없습니다 — Testcontainers 테스트가 실패합니다" >&2
  echo "  로컬: Docker Desktop 을 켜세요" >&2
  echo "  CI:   runner 에 /var/run/docker.sock 마운트가 필요합니다 (infra 소관)" >&2
  exit 1
fi

failed=()
step() {
  local name=$1
  shift
  printf '\n▶ %s\n' "$name"
  if "$@"; then
    printf '  ✓ %s\n' "$name"
  else
    printf '  ✗ %s\n' "$name" >&2
    failed+=("$name")
  fi
}

# 지금은 test 하나다. 정적 분석 등이 늘면 여기에 step 을 추가한다 — CI yml 은 그대로다.
step "테스트" ./gradlew --no-daemon test

# 첫 실패에서 멈추지 않고 전부 돌린 뒤 한 번에 보고한다.
printf '\n'
if [ ${#failed[@]} -eq 0 ]; then
  echo "✓ 전체 통과"
else
  printf '✗ 실패 %d건: %s\n' "${#failed[@]}" "${failed[*]}" >&2
  exit 1
fi
