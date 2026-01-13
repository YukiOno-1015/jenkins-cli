// Portal App  Build Pipeline
// このファイルはPortal Appプロジェクト専用のパイプライン設定です

@Library('jqit-lib@main') _

k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App.git',
  gitBranch: 'release1.0.0',
  gitSshCredentialsId: 'JQIT_ONO',
  mavenProfileChoices: ['dev', 'prod'],
  mavenDefaultProfile: 'dev',
  archivePattern: '**/target/portalApp-*.jar',
  enableSonarQube: true,
  sonarProjectName: 'Portal_App'
)