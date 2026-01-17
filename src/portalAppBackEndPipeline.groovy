// Portal App Backend Build Pipeline
// このファイルはPortal App Backendプロジェクト専用のパイプライン設定です
// 設定はvars/repositoryConfig.groovyで一元管理されています

@Library('jqit-lib@main') _

// repositoryConfigから全ての設定を自動取得
// 必要に応じてブランチなどを上書き可能
k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App_Backend.git',
  gitBranch: 'release1.0.0'
  // 以下の設定はrepositoryConfig.groovyから自動取得されます:
  // - gitSshCredentialsId
  // - mavenProfileChoices
  // - mavenDefaultProfile
  // - archivePattern
  // - enableSonarQube
  // - sonarProjectName
  // - k8s リソース設定
)