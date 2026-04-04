/*
 * Portal App 向けのビルドパイプラインです。
 *
 * このファイルでは `k8sMavenNodePipeline` を呼び出す最小構成だけを定義し、
 * 実際のビルド条件・認証情報・Kubernetes リソース設定は
 * `vars/repositoryConfig.groovy` と Shared Library 側へ委譲しています。
 *
 * 運用メモ:
 * - PR / ブランチビルドでは現在のブランチ名に追従した Shared Library を優先して読み込みます。
 * - ライブラリ読み込みに失敗した場合でも `main` へ自動フォールバックし、運用停止を避けます。
 */

def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
def libId = "jqit-lib@${libBranch}"

try {
  library libId
} catch (err) {
  echo "Failed to load ${libId}, falling back to jqit-lib@main"
  library 'jqit-lib@main'
}

// パラメータ定義（スクリプトパイプライン用）
properties([
  parameters([
    string(
      name: 'gitBranch',
      defaultValue: 'release1.0.0',
      description: 'Git branch to build (default: release1.0.0)'
    )
  ])
])

// 実際のビルド処理は Shared Library に集約し、このファイルでは
// 対象リポジトリ URL と、必要最小限の上書きパラメータのみを指定する。
//
// 補足:
// - `k8sMavenNodePipeline()` 自体が 1 本の Declarative Pipeline を生成するため、
//   後ろに別の `remoteSshPipeline()` を並べる形では連結できない。
// - そのため、JAR 転送 / リモートデプロイは `k8sMavenNodePipeline` の
//   オプション引数として同一パイプライン内へ組み込む。
k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App.git',
  gitBranch: params.gitBranch ?: 'release1.0.0',

  // Portal App は JAR 配布を想定しているため、成果物対象も JAR に合わせる。
  archivePattern: '**/target/*.jar',

  // `enableRemoteDeploy=true` で JAR を転送し、
  // `runDeployCommand=true` のときだけ配置反映や再起動まで実行する。
  enableRemoteDeploy: false,
  runDeployCommand: false,
  deployArtifactPattern: '**/target/*.jar',
  deployHost: '35.160.162.206',
  deployUser: 'ec2-user',
  deploySshCredentialsId: 'github-ssh',
  deployKnownHost: '35.160.162.206',
  deployTargetDir: '/opt/portal-app',
  deployUseSudo: true,
  deployCommand: 'sudo systemctl restart portal',
# ここで本番配置や再起動を必要に応じて実行する。
# 例:
# install -D -m 0644 "$DEPLOY_FIRST_ARTIFACT" /opt/portal-app/portalApp.jar
# systemctl restart portal

echo "Uploaded artifact: $DEPLOY_FIRST_ARTIFACT"
'''

  // 以下の設定は repositoryConfig.groovy から自動取得されます:
  // - gitSshCredentialsId
  // - mavenProfileChoices
  // - mavenDefaultProfile
  // - enableSonarQube
  // - sonarProjectName
  // - k8s リソース設定
)