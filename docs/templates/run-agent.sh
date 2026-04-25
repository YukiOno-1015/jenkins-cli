#!/bin/bash
set -euo pipefail

# =========================================================
# Jenkins inbound agent launcher for macOS launchd
# =========================================================
#
# 前提:
# - Jenkins controller には WebSocket で接続する
# - Java 21 を使う
# - secret は secret.txt から読む
# - Jenkins の作業ディレクトリは既存の /Users/your-user/jenkins を使う
# - /Users/your-user/jenkins は /Volumes/HDD/work への symlink を想定
#
# 重要:
# - launchd は通常のログインシェル環境と違う
# - Terminal手動実行では書けても、launchd経由では外部HDDに書けないことがある
# - そのため、agent起動前に cd / write test を行う
# =========================================================

# =========================================================
# 基本環境
# =========================================================
export HOME="/Users/your-user"
export USER="your-user"
export LOGNAME="your-user"
export SHELL="/bin/zsh"
export LANG="ja_JP.UTF-8"
export LC_ALL="ja_JP.UTF-8"

export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="${JAVA_HOME}/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

# =========================================================
# Jenkins agent 設定
# =========================================================
JAVA_BIN="${JAVA_HOME}/bin/java"
AGENT_JAR="/Users/your-user/jenkins-agent/agent.jar"
SECRET_FILE="/Users/your-user/jenkins-agent/secret.txt"

JENKINS_URL="https://your-jenkins.example.com/"
AGENT_NAME="machost"

# 既存構成を維持
# /Users/your-user/jenkins -> /Volumes/HDD/work
WORK_DIR="/Users/your-user/jenkins"

# ログ
LOG_DIR="/Users/your-user/jenkins-agent"
DEBUG_LOG="${LOG_DIR}/run-agent.debug.log"

mkdir -p "${LOG_DIR}"

# =========================================================
# デバッグ情報
# =========================================================
{
  echo "========== $(date) =========="
  echo "whoami: $(whoami)"
  echo "HOME: ${HOME}"
  echo "USER: ${USER}"
  echo "LOGNAME: ${LOGNAME}"
  echo "SHELL: ${SHELL}"
  echo "LANG: ${LANG}"
  echo "LC_ALL: ${LC_ALL}"
  echo "JAVA_HOME: ${JAVA_HOME}"
  echo "PATH: ${PATH}"
  echo "JAVA_BIN: ${JAVA_BIN}"
  echo "AGENT_JAR: ${AGENT_JAR}"
  echo "SECRET_FILE: ${SECRET_FILE}"
  echo "WORK_DIR: ${WORK_DIR}"
  echo "PWD before cd: $(pwd 2>&1 || true)"

  echo "--- java -version ---"
  "${JAVA_BIN}" -version

  echo "--- basic file check ---"
  /bin/ls -l "${JAVA_BIN}" 2>&1 || true
  /bin/ls -l "${AGENT_JAR}" 2>&1 || true
  /bin/ls -l "${SECRET_FILE}" 2>&1 || true
  /bin/ls -ld "${WORK_DIR}" 2>&1 || true

  echo "--- symlink / realpath check ---"
  /usr/bin/stat -f "WORK_DIR stat target: %Y" "${WORK_DIR}" 2>&1 || true
  /bin/ls -ld /Volumes 2>&1 || true
  /bin/ls -ld /Volumes/HDD 2>&1 || true
  /bin/ls -ld /Volumes/HDD/work 2>&1 || true

  echo "--- mount check ---"
  /sbin/mount | grep -E '/Volumes/HDD|HDD' || true

  echo "--- secret check ---"
  echo "secret length: $(tr -d '\r\n' < "${SECRET_FILE}" | wc -c | tr -d ' ')"
} >> "${DEBUG_LOG}" 2>&1

# =========================================================
# HDD / symlink 先が使えるまで待機
# =========================================================
WORK_DIR_READY="false"

for i in $(seq 1 60); do
  {
    echo "--- WORK_DIR readiness attempt ${i} ---"
    echo "checking: ${WORK_DIR}"
  } >> "${DEBUG_LOG}" 2>&1

  if [ -d "${WORK_DIR}" ] && [ -x "${WORK_DIR}" ] && [ -w "${WORK_DIR}" ]; then
    if cd "${WORK_DIR}" >> "${DEBUG_LOG}" 2>&1; then
      {
        echo "cd WORK_DIR: OK"
        echo "PWD after cd: $(pwd)"

        echo "--- write test ---"
        date > "${WORK_DIR}/.launchd_jenkins_write_test"
        /bin/ls -l "${WORK_DIR}/.launchd_jenkins_write_test"
        rm -f "${WORK_DIR}/.launchd_jenkins_write_test"
        echo "write test: OK"
      } >> "${DEBUG_LOG}" 2>&1

      WORK_DIR_READY="true"
      break
    else
      echo "cd WORK_DIR: NG" >> "${DEBUG_LOG}" 2>&1
    fi
  else
    {
      echo "WORK_DIR is not ready"
      echo "test -d: $([ -d "${WORK_DIR}" ] && echo OK || echo NG)"
      echo "test -x: $([ -x "${WORK_DIR}" ] && echo OK || echo NG)"
      echo "test -w: $([ -w "${WORK_DIR}" ] && echo OK || echo NG)"
    } >> "${DEBUG_LOG}" 2>&1
  fi

  sleep 2
done

if [ "${WORK_DIR_READY}" != "true" ]; then
  {
    echo "ERROR: WORK_DIR was not ready after waiting."
    echo "WORK_DIR: ${WORK_DIR}"
    echo "--- final ls ---"
    /bin/ls -ld "${WORK_DIR}" 2>&1 || true
    /bin/ls -ld /Volumes/HDD 2>&1 || true
    /bin/ls -ld /Volumes/HDD/work 2>&1 || true
  } >> "${DEBUG_LOG}" 2>&1
  exit 1
fi

# =========================================================
# secret 読み込み
# =========================================================
SECRET="$(tr -d '\r\n' < "${SECRET_FILE}")"

# =========================================================
# Jenkins agent 起動
# =========================================================
{
  echo "--- starting Jenkins agent ---"
  echo "JENKINS_URL: ${JENKINS_URL}"
  echo "AGENT_NAME: ${AGENT_NAME}"
  echo "WORK_DIR: ${WORK_DIR}"
  echo "PWD: $(pwd)"
} >> "${DEBUG_LOG}" 2>&1

exec "${JAVA_BIN}" \
  -Dhudson.slaves.ChannelPinger.pingIntervalSeconds=30 \
  -Dhudson.slaves.ChannelPinger.pingTimeoutSeconds=120 \
  -Djenkins.slaves.restarter.JnlpSlaveRestarterInstaller.disabled=true \
  -jar "${AGENT_JAR}" \
  -url "${JENKINS_URL}" \
  -secret "${SECRET}" \
  -name "${AGENT_NAME}" \
  -webSocket \
  -workDir "${WORK_DIR}"
