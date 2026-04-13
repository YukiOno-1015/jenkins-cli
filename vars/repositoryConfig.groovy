/*
 * リポジトリ別のビルド設定・認証情報・Kubernetes リソース要件を一元管理する設定台帳です。
 *
 * 運用意図:
 * - 各 Jenkinsfile / Shared Library から個別設定を追い出し、保守ポイントを一本化する
 * - 新規リポジトリ追加時はここへ定義を足すだけで、他のパイプライン側の変更を最小化する
 * - 未定義リポジトリに対しても安全なデフォルト設定で実行可能にする
 */

/**
 * リポジトリ名または URL を受け取り、対応する設定マップを返す。
 */
def call(String repoNameOrUrl) {
  // リポジトリ名を抽出
  def repoName = repoNameOrUrl
  if (repoNameOrUrl.contains('github.com') || repoNameOrUrl.contains('git@')) {
    repoName = extractRepoName(repoNameOrUrl)
  }
  
  echo "Getting configuration for repository: ${repoName}"

  // SSH リモート配置先の候補はここで共通管理する。
  // 新しい配備先が増えた場合は、このマップへ追記すれば各 Jenkinsfile 側の変更を最小化できる。
  def sharedDeployHostConfigs = [
    '52.192.35.208': [
      deployUser: 'ec2-user',
      deploySshCredentialsId: 'github-ssh',
      deployPort: 22
    ],
    '54.148.157.219': [
      deployUser: 'ec2-user',
      deploySshCredentialsId: 'jqit-deploy-github-ssh',
      deployPort: 22
    ]
  ]
  
  // リポジトリ別の設定定義
  def configs = [
    'Portal_App': [
      // 認証情報
      credentialsId: 'jqit-github-ssh',
      
      // ビルド設定
      buildProfiles: ['dev', 'prod'],
      defaultProfile: 'dev',
      
      // 成果物
      archivePattern: '**/target/portalApp-*.jar',

      // リモート配置先候補は sharedDeployHostConfigs を唯一の情報源として利用する。
      deployHostConfigs: sharedDeployHostConfigs,
      
      // SonarQube
      sonarProjectName: 'Portal_App',
      sonarEnabled: true,
      
      // テスト
      skipTestsByDefault: false,
      
      // リソース要件（K8s）
      k8s: [
        image: 'honoka4869/jenkins-maven-node:latest',
        cpuRequest: '500m',
        memRequest: '2Gi',
        cpuLimit: '2',
        memLimit: '4Gi'
      ]
    ],
    
    'Portal_App_Backend': [
      // 認証情報
      credentialsId: 'jqit-github-ssh',
      
      // ビルド設定
      buildProfiles: ['dev', 'local', 'prod'],
      defaultProfile: 'dev',
      
      // 成果物
      archivePattern: '**/target/portalApp-Api_*.jar',

      // リモート配置先候補は sharedDeployHostConfigs を唯一の情報源として利用する。
      deployHostConfigs: sharedDeployHostConfigs,
      
      // SonarQube
      sonarProjectName: 'Portal_App_Backend',
      sonarEnabled: true,
      
      // テスト
      skipTestsByDefault: false,
      
      // リソース要件（K8s）
      k8s: [
        image: 'honoka4869/jenkins-maven-node:latest',
        cpuRequest: '500m',
        memRequest: '2Gi',
        cpuLimit: '2',
        memLimit: '4Gi'
      ]
    ],
    
    // ============================================================
    // Python プロジェクトの例
    // ============================================================
    'PythonSampleProject': [
      // 認証情報
      credentialsId: 'jqit-github-ssh',
      
      // ビルド設定
      buildProfiles: ['dev', 'prod'],
      defaultProfile: 'dev',
      
      // 成果物（Python wheelまたは tarball）
      archivePattern: 'dist/*.whl',
      
      // SonarQube
      sonarProjectName: 'PythonSampleProject',
      sonarEnabled: false,  // Python用のSonarQubeセットアップが必要な場合はtrue
      
      // テスト
      skipTestsByDefault: false,
      
      // ビルドコマンド（Python/uvの場合）
      buildCommand: 'uv build',
      testCommand: 'uv run pytest',
      
      // リソース要件（K8s）
      k8s: [
        image: 'honoka4869/jenkins-maven-node:latest',
        cpuRequest: '500m',
        memRequest: '1Gi',
        cpuLimit: '2',
        memLimit: '2Gi'
      ]
    ]
    
    // 新しいリポジトリを追加する場合はここに追記
    // 'NewRepo': [
    //   credentialsId: 'NEW_CREDENTIALS_ID',
    //   buildProfiles: ['dev', 'prod'],
    //   defaultProfile: 'dev',
    //   archivePattern: '**/target/*.jar',
    //   sonarProjectName: 'NewRepo',
    //   sonarEnabled: true,
    //   skipTestsByDefault: false,
    //   k8s: [
    //     image: 'honoka4869/jenkins-maven-node:latest',
    //     cpuRequest: '1',
    //     memRequest: '4Gi',
    //     cpuLimit: '4',
    //     memLimit: '8Gi'
    //   ]
    // ]
  ]
  
  def config = configs.get(repoName)
  
  if (!config) {
    echo "⚠️  WARNING: No configuration found for repository: ${repoName}"
    echo "Using default configuration"
    
    // デフォルト設定
    config = [
      credentialsId: 'jqit-github-ssh',
      buildProfiles: ['dev', 'prod'],
      defaultProfile: 'dev',
      archivePattern: '**/target/*.jar',
      sonarProjectName: repoName,
      sonarEnabled: true,
      skipTestsByDefault: false,
      k8s: [
        image: 'honoka4869/jenkins-maven-node:latest',
        cpuRequest: '500m',
        memRequest: '2Gi',
        cpuLimit: '2',
        memLimit: '4Gi'
      ]
    ]
  }
  
  // デフォルト値の設定（個別設定で省略された項目を補完）
  if (!config.archivePattern) {
    echo "⚠️  WARNING: archivePattern not defined for ${repoName}, using default"
    config.archivePattern = '**/target/*.jar'
  }
  
  if (!config.skipArchive) {
    config.skipArchive = false
  }
  
  // リポジトリ名を設定に追加
  config.repoName = repoName
  
  echo "✅ Configuration loaded for ${repoName}"
  echo "  Credentials: ${config.credentialsId}"
  echo "  Build Profiles: ${config.buildProfiles}"
  echo "  Archive Pattern: ${config.archivePattern}"
  echo "  SonarQube: ${config.sonarProjectName} (enabled: ${config.sonarEnabled})"
  
  return config
}

/**
 * SSH / HTTPS 形式の GitHub URL からリポジトリ名だけを抽出する。
 */
def extractRepoName(String repoUrl) {
  def repoName = ''
  
  if (repoUrl.contains('git@github.com:')) {
    // SSH形式: git@github.com:owner/repo.git
    repoName = repoUrl.replaceAll('git@github.com:', '')
                      .replaceAll('.git$', '')
                      .split('/')[1]
  } else if (repoUrl.contains('github.com/')) {
    // HTTPS形式: https://github.com/owner/repo.git
    repoName = repoUrl.replaceAll('https?://github.com/', '')
                      .replaceAll('.git$', '')
                      .split('/')[1]
  } else {
    echo "⚠️  WARNING: Could not extract repository name from: ${repoUrl}"
    repoName = 'unknown'
  }
  
  return repoName
}

/**
 * 現在の Jenkins ビルド環境 (`env.GIT_URL`) から設定を解決する。
 */
def getCurrent() {
  if (env.GIT_URL) {
    return call(env.GIT_URL)
  } else {
    echo "⚠️  WARNING: GIT_URL environment variable not found"
    return call('unknown')
  }
}

/**
 * 後方互換用に credentialsId だけを返す簡易アクセサ。
 */
def getCredentialsId(String repoNameOrUrl) {
  def config = call(repoNameOrUrl)
  return config.credentialsId
}

/**
 * 主要リポジトリの設定一覧をまとめて返す。
 * ドキュメント生成や検証用途での参照を想定している。
 */
def getAll() {
  def repoNames = ['Portal_App', 'Portal_App_Backend']
  def allConfigs = [:]
  
  repoNames.each { name ->
    allConfigs[name] = call(name)
  }
  
  return allConfigs
}
