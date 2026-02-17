def call(Map args = [:]) {
    // 必須パラメータの検証
    if (!args.repoUrl) {
        error('repoUrl is required')
    }

    def repoUrl   = args.repoUrl
    def branch    = args.get('branch', 'main')
    def dirName   = args.get('dir', 'repo')
    def knownHost = args.get('knownHost', 'github.com')

    // sshCredentialsIdが指定されていなければrepositoryConfigから取得
    def sshCred = args.sshCredentialsId
    if (!sshCred) {
        def config = repositoryConfig(repoUrl)
        sshCred = config.credentialsId
        echo "Using credentials from repositoryConfig: ${sshCred}"
    }

    if (!sshCred) {
        error('sshCredentialsId could not be determined')
    }

    sshagent(credentials: [sshCred]) {
        sh """#!/bin/bash
          set -euo pipefail

          # ディレクトリのクリーンアップ
          rm -rf '${dirName}' || true

          # SSH known_hosts の設定（StrictHostKeyChecking を維持したまま通す）
          mkdir -p ~/.ssh
          chmod 700 ~/.ssh
          touch ~/.ssh/known_hosts
          chmod 600 ~/.ssh/known_hosts

          echo "Adding ${knownHost} to known_hosts (rsa/ecdsa/ed25519)"
          # keyscan は stderr にも出るので捨てる。失敗しても clone 側で落ちるのでここは継続
          ssh-keyscan -t rsa,ecdsa,ed25519 -H ${knownHost} >> ~/.ssh/known_hosts 2>/dev/null || true
          # 重複排除して安定化
          sort -u ~/.ssh/known_hosts -o ~/.ssh/known_hosts || true

          # Git が必ずこの known_hosts を使うように固定
          export GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=yes -o UserKnownHostsFile=\$HOME/.ssh/known_hosts"

          # Gitクローン
          echo "Cloning repository: ${repoUrl} (branch: ${branch})"
          git clone --depth 1 --single-branch --branch '${branch}' '${repoUrl}' '${dirName}'

          cd '${dirName}'
          echo "Repository cloned successfully"
          git rev-parse --abbrev-ref HEAD
          git log -1 --oneline || true
        """
    }
}