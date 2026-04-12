/*
 * 統合 GitHub Webhook パイプラインの Shared Library 版です。
 *
 * 使用例:
 *   @Library('jqit-lib@main') _
 *   unifiedWebhookPipeline()
 *
 * 主な役割:
 * - Webhook 起点の checkout / 設定取得 / Jenkinsfile 判定を共通化する
 * - 個別 Jenkinsfile が無いリポジトリにも最低限のテスト・SonarQube フローを提供する
 * - パイプライン設定のデフォルト値を一箇所にまとめ、呼び出し側を簡潔に保つ
 */

/**
 * 統合 Webhook パイプラインを起動する。
 * `config` で namespace やデフォルトパラメータを必要最小限だけ上書きできる。
 */
def call(Map config = [:]) {
  // デフォルト設定
  def defaults = [
    namespace: 'jenkins',
    k8sImage: 'honoka4869/jenkins-maven-node:latest',
    k8sImagePullSecret: 'dockerhub-jenkins-agent',
    k8sCpuRequest: '500m',
    k8sMemRequest: '2Gi',
    k8sCpuLimit: '2',
    k8sMemLimit: '4Gi',
    defaultBuildProfile: 'dev',
    defaultSkipTests: false,
    defaultRunSonarQube: true
  ]
  
  def cfg = defaults + config
  
  pipeline {
    agent {
      kubernetes {
        namespace cfg.namespace
        defaultContainer 'build'
        yaml k8sPodYaml(
          image: cfg.k8sImage,
          imagePullSecret: cfg.k8sImagePullSecret,
          cpuRequest: cfg.k8sCpuRequest,
          memRequest: cfg.k8sMemRequest,
          cpuLimit: cfg.k8sCpuLimit,
          memLimit: cfg.k8sMemLimit
        )
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
        defaultValue: cfg.defaultSkipTests,
        description: 'テストをスキップ'
      )
      booleanParam(
        name: 'RUN_SONARQUBE',
        defaultValue: cfg.defaultRunSonarQube,
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
            def webhookEvent = env.CHANGE_ID ? 'pull_request' : 'push'
            env.WEBHOOK_EVENT = webhookEvent
            
            echo "Webhook イベント: ${webhookEvent}"
            echo "プルリクエスト: ${env.CHANGE_ID ?: 'N/A'}"
            
            // 認証情報付きチェックアウト（設定も一括取得）
            def checkoutInfo = authenticatedCheckout()
            def config = checkoutInfo.config
            
            // 環境変数に保存
            env.SONAR_PROJECT_NAME = config.sonarProjectName
            env.SONAR_ENABLED = config.sonarEnabled
            
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
            
            withSonarQubeEnv('SonarQube') {
              sh """
                mvn sonar:sonar \
                  -Dsonar.projectKey=${env.SONAR_PROJECT_NAME} \
                  -Dsonar.projectName=${env.SONAR_PROJECT_NAME}
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
}
