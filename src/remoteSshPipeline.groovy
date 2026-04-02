// remoteSsh shared library の疎通確認用 Pipeline
// 運用影響のない読み取り専用コマンドだけを実行する

def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
def libId = "jqit-lib@${libBranch}"

try {
  library libId
} catch (err) {
  echo "Failed to load ${libId}, falling back to jqit-lib@main"
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
    string(
      name: 'SSH_CREDENTIALS_ID',
      defaultValue: 'MACHOST_SSH',
      description: 'Jenkins の SSH Username with private key credentials ID'
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
          echo 'remoteSsh Test Pipeline'
          echo '========================================='
          echo "Target Host: ${params.TARGET_HOST}"
          echo "SSH User: ${params.SSH_USER}"
          echo "SSH Credentials ID: ${params.SSH_CREDENTIALS_ID}"
          echo "Known Host: ${params.KNOWN_HOST?.trim() ? params.KNOWN_HOST.trim() : params.TARGET_HOST}"
          echo "Use sudo: ${params.USE_SUDO}"
          echo "Strict host key checking: ${params.STRICT_HOST_KEY_CHECKING}"
          echo 'Command: remote OS version check only'
          echo '========================================='
        }
      }
    }

    stage('Check Remote OS Version') {
      steps {
        script {
          def knownHost = params.KNOWN_HOST?.trim() ? params.KNOWN_HOST.trim() : params.TARGET_HOST
          def osInfo = remoteSsh(
            host: params.TARGET_HOST.trim(),
            user: params.SSH_USER.trim(),
            sshCredentialsId: params.SSH_CREDENTIALS_ID.trim(),
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
          echo 'Remote OS information'
          echo '========================================='
          echo osInfo
          echo '========================================='
        }
      }
    }
  }

  post {
    success {
      echo 'remoteSsh test completed successfully'
    }

    failure {
      echo 'remoteSsh test failed. Check host, user, credentials, and known_hosts settings.'
    }
  }
}