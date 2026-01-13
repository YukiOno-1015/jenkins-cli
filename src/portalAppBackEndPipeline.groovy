// Portal App Backend Build Pipeline
// このファイルはPortal App Backendプロジェクト専用のパイプライン設定です

@Library('jqit-lib@main') _

k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App_Backend.git',
  gitBranch: 'release1.0.0',
  gitSshCredentialsId: 'JQIT_ONO',
  mavenProfileChoices: ['dev', 'local', 'prod'],
  mavenDefaultProfile: 'dev',
  archivePattern: '**/target/portalApp-Api_*.jar',
  enableSonarQube: true
)