// 統合GitHub Webhook Pipeline
// 1つのWebhookエンドポイントで複数リポジトリを処理
// 
// 動作:
// 1. リポジトリにJenkinsfileがあれば、それを使用
// 2. なければ、デフォルトでユニットテスト+SonarQubeを実行

@Library('jqit-lib@main') _

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
          echo "Unified GitHub Webhook Pipeline"
          echo "========================================="
          
          // Webhook イベントタイプを検出
          def webhookEvent = detectWebhookEvent()
          env.WEBHOOK_EVENT = webhookEvent
          
          echo "Webhook Event: ${webhookEvent}"
          echo "Pull Request: ${env.CHANGE_ID ?: 'N/A'}"
          
          // 認証情報付きチェックアウト（設定も一括取得）
          def checkoutInfo = authenticatedCheckout()
          def config = checkoutInfo.config
          
          echo "SonarQube Project: ${config.sonarProjectName}"
          echo "SonarQube Enabled: ${config.sonarEnabled}"
          
          // Jenkinsfile の存在確認
          env.HAS_JENKINSFILE = fileExists('Jenkinsfile') ? 'true' : 'false'
          echo "Jenkinsfile exists: ${env.HAS_JENKINSFILE}"
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
          echo "Found Jenkinsfile - Executing custom pipeline"
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
          echo "No Jenkinsfile - Running default tests"
          echo "Running Tests - ${env.REPO_NAME}"
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
          echo "No Jenkinsfile - Running default SonarQube analysis"
          echo "SonarQube Analysis - ${env.REPO_NAME}"
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
        echo "Pipeline Completed - ${env.REPO_NAME}"
        echo "========================================="
        echo "Build Result: ${currentBuild.result ?: 'SUCCESS'}"
        echo "Duration: ${currentBuild.durationString}"
      }
    }
    
    success {
      script {
        echo "✅ ${env.REPO_NAME} build succeeded!"
      }
    }
    
    failure {
      script {
        echo "❌ ${env.REPO_NAME} build failed!"
      }
    }
    
    unstable {
      script {
        echo "⚠️ ${env.REPO_NAME} build is unstable!"
      }
    }
  }
}

// ============================================================
// ヘルパー関数
// ============================================================

// Webhook イベントタイプを検出
def detectWebhookEvent() {
  // Multibranch Pipeline の場合
  if (env.CHANGE_ID) {
    return 'pull_request'
  }
  
  // Push イベント
  return 'push'
}
