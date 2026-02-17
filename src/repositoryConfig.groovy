// Repository Configuration Manager
// リポジトリに関する全ての設定を一元管理
// - 認証情報ID
// - ビルド設定
// - アーカイブパターン
// - SonarQube設定
// など

def call(String repoNameOrUrl) {
  // リポジトリ名を抽出
  def repoName = repoNameOrUrl
  if (repoNameOrUrl.contains('github.com') || repoNameOrUrl.contains('git@')) {
    repoName = extractRepoName(repoNameOrUrl)
  }
  
  echo "Getting configuration for repository: ${repoName}"
  
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

// リポジトリURLからリポジトリ名を抽出
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

// 現在のビルドコンテキストから設定を取得
def getCurrent() {
  if (env.GIT_URL) {
    return call(env.GIT_URL)
  } else {
    echo "⚠️  WARNING: GIT_URL environment variable not found"
    return call('unknown')
  }
}

// 認証情報IDのみを取得（後方互換性のため）
def getCredentialsId(String repoNameOrUrl) {
  def config = call(repoNameOrUrl)
  return config.credentialsId
}

// 複数リポジトリの設定を一括取得
def getAll() {
  def repoNames = ['Portal_App', 'Portal_App_Backend']
  def allConfigs = [:]
  
  repoNames.each { name ->
    allConfigs[name] = call(name)
  }
  
  return allConfigs
}
