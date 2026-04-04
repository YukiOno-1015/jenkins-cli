/*
 * Portal App Backend 向けのビルドパイプラインです。
 *
 * ここでは対象リポジトリとブランチ指定だけを薄く定義し、
 * 認証情報・Maven/SonarQube 設定・Kubernetes Pod 設定などの詳細は
 * Shared Library と `vars/repositoryConfig.groovy` に集約しています。
 *
 * 運用メモ:
 * - 現在の変更ブランチと同名の Shared Library を優先して読み込みます。
 * - 読み込み失敗時は `main` にフォールバックして継続します。
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

// 実ビルド本体は Shared Library に委譲し、このファイルは
// 「Backend をどのブランチでビルドするか」を明示するエントリポイントとして扱う。
k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App_Backend.git',
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