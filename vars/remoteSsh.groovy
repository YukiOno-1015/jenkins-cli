/*
 * Jenkins から安全に SSH 実行を行うための Shared Library です。
 *
 * 目的:
 * - 受け取った command / script を安全にリモートへ渡す
 * - StrictHostKeyChecking の有無を切り替え可能にする
 * - `returnStdout` により、参照専用の取得処理にも更新処理にも再利用できるようにする
 */

/**
 * シェル引数として安全に埋め込めるよう、値を単一引用符でエスケープする。
 */
def shellQuote(String value) {
    return "'${(value ?: '').replace("'", "'\"'\"'")}'"
}

/**
 * `command` または `script` のどちらか一方だけを受け取り、
 * リモート側で `set -euo pipefail` を有効にした実行スクリプトを構築する。
 */
def buildRemoteScript(Map args) {
    def command = args.command?.toString()
    def script = args.script?.toString()

    if (command && script) {
        error('Specify either command or script, not both')
    }

    if (!command && !script) {
        error('command or script is required')
    }

    def payload = script ?: command

    return """
set -euo pipefail

${payload}
""".trim()
}

/**
 * SSH コマンド実行のエントリポイント。
 * 必須項目は `host`, `user`, `sshCredentialsId` で、必要に応じて `port`,
 * `knownHost`, `useSudo`, `strictHostKeyChecking`, `returnStdout` を上書きできる。
 */
def call(Map args = [:]) {
    def host = args.host?.toString()?.trim()
    def user = args.user?.toString()?.trim()
    def sshCredentialsId = args.sshCredentialsId?.toString()?.trim()

    if (!host) {
        error('host is required')
    }

    if (!user) {
        error('user is required')
    }

    if (!sshCredentialsId) {
        error('sshCredentialsId is required')
    }

    def port = args.get('port', 22) as Integer
    def connectTimeoutSeconds = args.get('connectTimeoutSeconds', 10) as Integer
    def knownHost = args.get('knownHost', host)?.toString()?.trim()
    def useSudo = (args.get('useSudo', false) as Boolean)
    def returnStdout = (args.get('returnStdout', false) as Boolean)
    def strictHostKeyChecking = (args.get('strictHostKeyChecking', true) as Boolean)
    def remoteScript = buildRemoteScript(args)
    def remoteEntrypoint = useSudo ? 'sudo -n bash -s' : 'bash -s'

    echo "Running remote SSH command on ${host} as ${user}${useSudo ? ' with sudo' : ''}"

    sshagent(credentials: [sshCredentialsId]) {
        def sshScript = """#!/bin/bash
set -euo pipefail

SSH_TARGET_HOST=${shellQuote(host)}
SSH_TARGET_USER=${shellQuote(user)}
SSH_PORT=${shellQuote(port.toString())}
SSH_KNOWN_HOST=${shellQuote(knownHost)}

if ${strictHostKeyChecking ? 'true' : 'false'}; then
  mkdir -p ~/.ssh
  chmod 700 ~/.ssh
  touch ~/.ssh/known_hosts
  chmod 600 ~/.ssh/known_hosts

  echo "Adding \${SSH_KNOWN_HOST} to known_hosts (rsa/ecdsa/ed25519)"
  ssh-keyscan -p "\${SSH_PORT}" -t rsa,ecdsa,ed25519 -H "\${SSH_KNOWN_HOST}" >> ~/.ssh/known_hosts 2>/dev/null || true
  sort -u ~/.ssh/known_hosts -o ~/.ssh/known_hosts || true

  SSH_HOST_KEY_OPTIONS=(
    -o StrictHostKeyChecking=yes
    -o UserKnownHostsFile="\$HOME/.ssh/known_hosts"
  )
else
  SSH_HOST_KEY_OPTIONS=(
    -o StrictHostKeyChecking=no
    -o UserKnownHostsFile=/dev/null
  )
fi

SSH_COMMON_OPTIONS=(
  -o BatchMode=yes
  -o ConnectTimeout=${connectTimeoutSeconds}
)

ssh "\${SSH_COMMON_OPTIONS[@]}" "\${SSH_HOST_KEY_OPTIONS[@]}" -p "\${SSH_PORT}" "\${SSH_TARGET_USER}@\${SSH_TARGET_HOST}" ${shellQuote(remoteEntrypoint)} <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
"""

        if (returnStdout) {
            return sh(script: sshScript, returnStdout: true).trim()
        }

        sh(script: sshScript)
        return null
    }
}
