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
k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App.git',
  gitBranch: params.gitBranch ?: 'release1.0.0'
  // 以下の設定はrepositoryConfig.groovyから自動取得されます:
  // - gitSshCredentialsId
  // - mavenProfileChoices
  // - mavenDefaultProfile
  // - archivePattern
  // - enableSonarQube
  // - sonarProjectName
  // - k8s リソース設定
)