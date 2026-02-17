// 統合GitHub Webhook Pipeline (Shared Library版)
// 使用例:
// @Library('jqit-lib@main') _
// unifiedWebhookPipeline()
//
// 動作:
// 1. リポジトリにJenkinsfileがあれば、それを使用
// 2. なければ、デフォルトでユニットテスト+SonarQubeを実行
// 
// 特徴:
// - リポジトリごとにカスタムパイプライン定義可能
// - repositoryConfig.groovyから自動設定取得

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
            echo "Unified GitHub Webhook Pipeline"
            echo "========================================="
            
            // Webhook イベントタイプを検出
            def webhookEvent = env.CHANGE_ID ? 'pull_request' : 'push'
            env.WEBHOOK_EVENT = webhookEvent
            
            echo "Webhook Event: ${webhookEvent}"
            echo "Pull Request: ${env.CHANGE_ID ?: 'N/A'}"
            
            // 認証情報付きチェックアウト（設定も一括取得）
            def checkoutInfo = authenticatedCheckout()
            def config = checkoutInfo.config
            
            // 環境変数に保存
            env.SONAR_PROJECT_NAME = config.sonarProjectName
            env.SONAR_ENABLED = config.sonarEnabled
            
            echo "SonarQube project: ${config.sonarProjectName}"
            echo "SonarQube enabled: ${config.sonarEnabled}"
            
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
}
