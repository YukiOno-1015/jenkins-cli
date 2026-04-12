/*
 * `remoteSsh` Shared Library の疎通確認を行うテスト用パイプラインです。
 *
 * 目的:
 * - SSH 鍵・known_hosts・接続オプションが正しく機能するかを Jenkins 上で検証する
 * - 破壊的な操作を避け、OS 情報の取得だけで接続性を確認する
 *
 * このジョブは本番変更ではなく、接続設定の確認に特化しています。
 */

def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
def libId = "jqit-lib@${libBranch}"

try {
  library libId
} catch (err) {
  echo "${libId} の読み込みに失敗しました。jqit-lib@main へフォールバックします。"
  library 'jqit-lib@main'
}

pipeline {
  agent {
    kubernetes {
      yaml k8sPodYaml(
        image: 'honoka4869/jenkins-maven-node:latest',
        imagePullSecret: 'docker-hub',
        cpuRequest: '250m',
        memRequest: '512Mi',
        cpuLimit: '1',
        memLimit: '1Gi'
      )
      defaultContainer 'build'
    }
  }

  parameters {
    string(
      name: 'TARGET_HOST',
      defaultValue: 'machost',
      description: 'SSH 接続先ホスト名または IP'
    )
    string(
      name: 'SSH_USER',
      defaultValue: 'yukiono',
      description: 'SSH 接続ユーザ'
    )
    credentials(
      name: 'SSH_CREDENTIALS_ID',
      credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
      defaultValue: 'jqit-github-ssh',
      description: 'Jenkins の SSH Username with private key credentials ID（例: jqit-github-ssh）'
    )
    string(
      name: 'KNOWN_HOST',
      defaultValue: '',
      description: 'known_hosts 登録に使うホスト名。空なら TARGET_HOST を使用'
    )
    booleanParam(
      name: 'USE_SUDO',
      defaultValue: false,
      description: 'sudo -n bash -s で実行する'
    )
    booleanParam(
      name: 'STRICT_HOST_KEY_CHECKING',
      defaultValue: true,
      description: 'known_hosts を使ったホスト鍵検証を有効にする'
    )
  }

  options {
    skipDefaultCheckout(true)
    buildDiscarder(logRotator(numToKeepStr: '20'))
    disableConcurrentBuilds()
    timestamps()
    timeout(time: 15, unit: 'MINUTES')
  }

  stages {
    stage('Check Agent Tools') {
      steps {
        sh '''#!/bin/bash
          set -euo pipefail

          command -v ssh >/dev/null 2>&1
          command -v ssh-keyscan >/dev/null 2>&1
        '''
      }
    }

    stage('Show Parameters') {
      steps {
        script {
          echo '========================================='
          echo 'remoteSsh テストパイプライン'
          echo '========================================='
          echo "接続先ホスト: ${params.TARGET_HOST}"
          echo "SSH ユーザー: ${params.SSH_USER}"
          echo "SSH Credentials ID: ${params.SSH_CREDENTIALS_ID}"
          echo "known_hosts 登録ホスト: ${params.KNOWN_HOST?.trim() ? params.KNOWN_HOST.trim() : params.TARGET_HOST}"
          echo "sudo 使用: ${params.USE_SUDO}"
          echo "ホスト鍵検証（StrictHostKeyChecking）: ${params.STRICT_HOST_KEY_CHECKING}"
          echo 'コマンド: リモート OS バージョン確認のみ'
          echo '========================================='
        }
      }
    }

    stage('Check Remote OS Version') {
      steps {
        script {
          // known_hosts に登録するホスト名を明示し、未指定時のみ TARGET_HOST を使う。
          def knownHost = params.KNOWN_HOST?.trim() ? params.KNOWN_HOST.trim() : params.TARGET_HOST

          // 疎通確認では OS 情報の読み取りだけを実行し、設定破壊や更新処理は行わない。
          def osInfo = remoteSsh(
            host: params.TARGET_HOST.trim(),
            user: params.SSH_USER.trim(),
            sshCredentialsId: params.SSH_CREDENTIALS_ID?.toString()?.trim(),
            knownHost: knownHost,
            useSudo: params.USE_SUDO,
            strictHostKeyChecking: params.STRICT_HOST_KEY_CHECKING,
            returnStdout: true,
            command: '''
if command -v sw_vers >/dev/null 2>&1; then
  sw_vers
elif [ -f /etc/os-release ]; then
  cat /etc/os-release
else
  uname -a
fi
'''
          )

          echo '========================================='
          echo 'リモート OS 情報'
          echo '========================================='
          echo osInfo
          echo '========================================='
        }
      }
    }
  }

  post {
    success {
      echo 'remoteSsh テストが正常に完了しました。'
    }

    failure {
      echo 'remoteSsh テストが失敗しました。ホスト・ユーザー・認証情報・known_hosts の設定を確認してください。'
    }
  }
}