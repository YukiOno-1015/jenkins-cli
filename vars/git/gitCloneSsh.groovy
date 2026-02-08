def call(Map args = [:]) {
    // 必須パラメータの検証
    if (!args.repoUrl) {
        error('repoUrl is required')
    }
    
    def repoUrl = args.repoUrl
    def branch  = args.get('branch', 'main')
    def dirName = args.get('dir', 'repo')
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
          if [ -d '${dirName}' ]; then
            echo "Removing existing directory: ${dirName}"
            rm -rf '${dirName}'
          fi
          
          # SSH known_hostsの設定
          mkdir -p ~/.ssh
          chmod 700 ~/.ssh
          if ! grep -q "${knownHost}" ~/.ssh/known_hosts 2>/dev/null; then
            echo "Adding ${knownHost} to known_hosts"
            ssh-keyscan -H ${knownHost} >> ~/.ssh/known_hosts
          fi
          
          # Gitクローン
          echo "Cloning repository: ${repoUrl} (branch: ${branch})"
          git clone --depth 1 --single-branch --branch '${branch}' '${repoUrl}' '${dirName}'
          
          cd '${dirName}'
          echo "Repository cloned successfully"
          ls -la
        """
    }
}