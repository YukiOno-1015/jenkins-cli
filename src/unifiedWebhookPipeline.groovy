/*
 * 複数リポジトリを 1 本のエンドポイントで受ける統合 GitHub Webhook パイプラインです。
 *
 * 基本動作:
 * 1. Webhook から対象リポジトリとイベント種別を特定する
 * 2. `authenticatedCheckout()` で認証付き checkout と設定読込をまとめて行う
 * 3. リポジトリに `Jenkinsfile` がある場合はその定義を優先する
 * 4. `Jenkinsfile` が無い場合は共通のテスト / SonarQube フローを実行する
 *
 * なるべく共通挙動をこのファイルへ集約し、個別リポジトリ側の Jenkinsfile は最小化する方針です。
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
        imagePullSecret: 'dockerhub-jenkins-agent',
        cpuRequest: '500m',
        memRequest: '2Gi',
        cpuLimit: '2',
        memLimit: '4Gi'
      )
      defaultContainer 'build'
    }
  }
  
  triggers {
    githubPush()
  }
  
  parameters {
    choice(
      name: 'BUILD_PROFILE',
      choices: ['dev', 'local', 'prod'],
      description: 'ビルドプロファイル'
    )
    booleanParam(
      name: 'SKIP_TESTS',
      defaultValue: false,
      description: 'テストをスキップ'
    )
    booleanParam(
      name: 'RUN_SONARQUBE',
      defaultValue: true,
      description: 'SonarQube解析を実行'
    )
  }
  
  options {
    buildDiscarder(logRotator(numToKeepStr: '10'))
    timeout(time: 30, unit: 'MINUTES')
    disableConcurrentBuilds()
    timestamps()
  }
  
  stages {
    stage('Repository Detection & Checkout') {
      steps {
        script {
          echo "========================================="
          echo "統合 GitHub Webhook パイプライン"
          echo "========================================="
          
          // Webhook イベントタイプを検出
          def webhookEvent = detectWebhookEvent()
          env.WEBHOOK_EVENT = webhookEvent
          
          echo "Webhook イベント: ${webhookEvent}"
          echo "プルリクエスト: ${env.CHANGE_ID ?: 'N/A'}"
          
          // 認証情報付きチェックアウト（設定も一括取得）
          def checkoutInfo = authenticatedCheckout()
          def config = checkoutInfo.config
          
          echo "SonarQube プロジェクト: ${config.sonarProjectName}"
          echo "SonarQube 有効: ${config.sonarEnabled}"
          
          // Jenkinsfile の存在確認
          env.HAS_JENKINSFILE = fileExists('Jenkinsfile') ? 'true' : 'false'
          echo "Jenkinsfile の存在: ${env.HAS_JENKINSFILE}"
          echo "========================================="
        }
      }
    }
    
    stage('Execute Custom Jenkinsfile') {
      when {
        expression { env.HAS_JENKINSFILE == 'true' }
      }
      steps {
        script {
          echo "========================================="
          echo "Jenkinsfile を検出 - カスタムパイプラインを実行します"
          echo "========================================="
          
          // カスタム Jenkinsfile を実行
          load 'Jenkinsfile'
        }
      }
    }
    
    stage('Test') {
      when {
        allOf {
          expression { env.HAS_JENKINSFILE == 'false' }
          expression { params.SKIP_TESTS == false }
        }
      }
      steps {
        script {
          echo "========================================="
          echo "Jenkinsfile なし - デフォルトテストを実行します"
          echo "テスト実行 - ${env.REPO_NAME}"
          echo "========================================="
        }
        
        sh 'mvn test'
      }
      post {
        always {
          junit '**/target/surefire-reports/*.xml'
        }
      }
    }
    
    stage('SonarQube Analysis') {
      when {
        allOf {
          expression { env.HAS_JENKINSFILE == 'false' }
          expression { params.RUN_SONARQUBE == true }
        }
      }
      steps {
        script {
          echo "========================================="
          echo "Jenkinsfile なし - デフォルト SonarQube 解析を実行します"
          echo "SonarQube 解析 - ${env.REPO_NAME}"
          echo "========================================="
          
          // リポジトリ設定を再取得
          def config = repositoryConfig(env.REPO_NAME)
          
          withSonarQubeEnv('SonarQube') {
            sh """
              mvn sonar:sonar \
                -Dsonar.projectKey=${config.sonarProjectName} \
                -Dsonar.projectName=${config.sonarProjectName}
            """
          }
        }
      }
    }
  }
  
  post {
    always {
      script {
        echo "========================================="
        echo "パイプライン完了 - ${env.REPO_NAME}"
        echo "========================================="
        echo "ビルド結果: ${currentBuild.result ?: 'SUCCESS'}"
        echo "実行時間: ${currentBuild.durationString}"
      }
    }
    
    success {
      script {
        echo "✅ ${env.REPO_NAME} のビルドが成功しました!"
      }
    }
    
    failure {
      script {
        echo "❌ ${env.REPO_NAME} のビルドが失敗しました!"
      }
    }
    
    unstable {
      script {
        echo "⚠️ ${env.REPO_NAME} のビルドが不安定です!"
      }
    }
  }
}

// ============================================================
// ヘルパー関数
// ============================================================

/**
 * Jenkins のビルドコンテキストから Webhook 種別を判定する。
 * 現在は PR ビルドなら `pull_request`、それ以外は `push` として扱う。
 */
def detectWebhookEvent() {
  // Multibranch Pipeline の場合
  if (env.CHANGE_ID) {
    return 'pull_request'
  }
  
  // Push イベント
  return 'push'
}
