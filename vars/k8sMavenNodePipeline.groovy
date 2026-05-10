/*
 * Kubernetes 上の Jenkins agent で Maven / Node 系ビルドを標準化する共有パイプラインです。
 *
 * 役割:
 * - Git clone / Maven build / SonarQube / artifact archive の一連の流れを共通化する
 * - リポジトリ固有設定は `repositoryConfig()` から自動取得する
 * - 例外的な差分だけを `cfg` から上書きし、各 Jenkinsfile を薄く保つ
 */

/**
 * 標準ビルドパイプラインを生成して実行する。
 * `gitRepoUrl` は必須で、その他の値は `cfg > repositoryConfig > デフォルト値` の順で解決する。
 */
def call(Map cfg = [:]) {
    // ---- gitRepoUrlは必須 ----
    def gitRepoUrl = cfg.gitRepoUrl ?: error('gitRepoUrl is required')

    // ---- repositoryConfigから設定を取得 ----
    def repoConfig = repositoryConfig(gitRepoUrl)
    echo "リポジトリ設定を読み込みました: ${repoConfig.repoName}"

    // ---- 設定の優先順位: 引数 > repositoryConfig > デフォルト値 ----
    def k8sNamespace = cfg.get('namespace', 'jenkins')
    // NOTE: これは "Kubernetes Secret 名" (spec.imagePullSecrets[].name)
    def imagePullSecret = cfg.get('imagePullSecret', 'nexus')

    def gitBranch = cfg.get('gitBranch', 'release1.0.0')
    def configuredGitCredId = repoConfig.credentialsId?.toString()?.trim()
    def requestedGitCredId = cfg.containsKey('gitSshCredentialsId') ? cfg.get('gitSshCredentialsId') : null
    requestedGitCredId = requestedGitCredId?.toString()?.trim()
    def gitSshCredId = requestedGitCredId ?: configuredGitCredId

    if (!gitSshCredId) {
        error("Git SSH credentials ID could not be resolved for ${repoConfig.repoName}. Set repositoryConfig.credentialsId or gitSshCredentialsId.")
    }

    def gitCredSource = requestedGitCredId ? 'pipeline argument' : 'repositoryConfig'
    echo "Git SSH 認証情報 ID: ${gitSshCredId}（取得元: ${gitCredSource}）"

    def mavenProfileChoices = cfg.get('mavenProfileChoices', repoConfig.buildProfiles)
    def mavenDefaultProfile = cfg.get('mavenDefaultProfile', repoConfig.defaultProfile)
    def mavenCommandOverridden = cfg.containsKey('mavenCommand')
    def mavenCommand = cfg.get('mavenCommand', 'mvn -B clean package')

    // ---- JUnit / Coverage 公開設定 ----
    // 既定方針:
    // - JUnit / Coverage の「公開」は常に行う（junit / recordCoverage / Markdown 表）
    // - JaCoCo の「実行」は pom.xml 側のプラグイン設定に任せ、CLI 注入はデフォルト OFF
    //
    // 過去の事故: pom.xml 側で jacoco-maven-plugin が定義済みのリポジトリ
    //   （例: Portal_App_Backend の 0.8.11）に対して CLI から 0.8.12 を追加注入すると、
    //   surefire fork に `-javaagent` が 2 重に渡って `jacoco.exec` を奪い合い、
    //   JVM が exit 134 でクラッシュする。
    //   pom.xml が JaCoCo を持たないリポでは opt-in で `injectJacoco: true` を指定する。
    def enableJUnit = cfg.containsKey('enableJUnit') ? cfg.get('enableJUnit').toString().toBoolean() : true
    def enableCoverage = cfg.containsKey('enableCoverage') ? cfg.get('enableCoverage').toString().toBoolean() : true
    def jacocoMavenPluginVersion = cfg.get('jacocoMavenPluginVersion', '0.8.12')?.toString()?.trim()
    def junitPattern = cfg.get('junitPattern', '**/target/surefire-reports/*.xml')?.toString()?.trim()
    def coveragePattern = cfg.get('coveragePattern', '**/target/site/jacoco/jacoco.xml')?.toString()?.trim()

    // CLI 注入はデフォルト OFF。pom.xml に JaCoCo が無いリポでだけ true にする。
    // mavenCommand が cfg で明示上書きされている場合も注入は無効化する（独自コマンドを尊重）。
    def injectJacocoRequested = cfg.containsKey('injectJacoco') ? cfg.get('injectJacoco').toString().toBoolean() : false
    def injectJacoco = injectJacocoRequested && !mavenCommandOverridden
    def jacocoPrepareGoal = injectJacoco ? "org.jacoco:jacoco-maven-plugin:${jacocoMavenPluginVersion}:prepare-agent" : ''
    def jacocoReportGoal = injectJacoco ? "org.jacoco:jacoco-maven-plugin:${jacocoMavenPluginVersion}:report" : ''
    if (injectJacoco) {
        // 既定の `mvn -B clean package` に対し、`clean` の直後に prepare-agent を、末尾に report を差し込む
        mavenCommand = mavenCommand.replaceFirst(/\bclean\b/, "clean ${jacocoPrepareGoal}") + " ${jacocoReportGoal}"
    } else if (injectJacocoRequested && mavenCommandOverridden) {
        echo "⚠️ mavenCommand が上書きされているため、JaCoCo の CLI 注入をスキップしました。" +
             " カバレッジを公開したい場合は mavenCommand 側で prepare-agent / report を含めてください。"
    }

    def archivePattern = cfg.get('archivePattern', repoConfig.archivePattern)
    def skipArchive = cfg.get('skipArchive', repoConfig.skipArchive)

    // リモート配置設定。通常は `repositoryConfig.groovy` の `deployHostConfigs` を
    // 単一の truth source とし、ここからホスト候補と接続情報を解決する。
    // 情報不足の設定は fail-fast で止め、曖昧なフォールバックは行わない。
    def enableRemoteDeploy = cfg.containsKey('enableRemoteDeploy') ? cfg.get('enableRemoteDeploy').toString().toBoolean() : false
    def deployArtifactPattern = cfg.get('deployArtifactPattern', archivePattern ?: '**/target/*.jar')?.toString()?.trim()
    def deployHostConfigs = [:]
    def deployHostConfigsRaw = cfg.containsKey('deployHostConfigs') ? (cfg.get('deployHostConfigs', [:]) ?: [:]) : (repoConfig.get('deployHostConfigs', [:]) ?: [:])
    if (deployHostConfigsRaw instanceof Map) {
        deployHostConfigsRaw.each { hostKey, hostCfg ->
            def normalizedKey = hostKey?.toString()?.trim()
            if (normalizedKey) {
                deployHostConfigs[normalizedKey] = hostCfg instanceof Map ? hostCfg : [:]
            }
        }
    }
    def deployKnownHostChoiceList = deployHostConfigs.keySet().findAll { it?.toString()?.trim() }.collect { it.toString().trim() }.sort()
    def deployKnownHostChoices = deployKnownHostChoiceList ? deployKnownHostChoiceList.join('\n') : 'not-configured'
    if (deployKnownHostChoiceList) {
        echo "リモート配備ホスト候補: ${deployKnownHostChoiceList.join(', ')}"
    } else {
        echo "⚠️  ${repoConfig.repoName} のリモート配備ホスト候補が未設定です"
    }
    def deployTargetDir = cfg.get('deployTargetDir', '/tmp/app-deploy')?.toString()?.trim()
    def deployUseSudo = cfg.containsKey('deployUseSudo') ? cfg.get('deployUseSudo').toString().toBoolean() : false
    def deployUploadDirDefault = deployUseSudo ? "/tmp/${sanitizeForPathSegment(repoConfig.repoName ?: 'app')}-deploy" : deployTargetDir
    def deployUploadDir = cfg.get('deployUploadDir', deployUploadDirDefault)?.toString()?.trim()
    def deployCommand = cfg.get('deployCommand', '')?.toString()
    def runDeployCommand = cfg.containsKey('runDeployCommand') ? cfg.get('runDeployCommand').toString().toBoolean() : false

    def enableSonarQube = cfg.get('enableSonarQube', repoConfig.sonarEnabled)
    def configuredSonarCredId = repoConfig.sonarQubeCredentialsId?.toString()?.trim()
    def requestedSonarCredId = cfg.containsKey('sonarQubeCredentialsId') ? cfg.get('sonarQubeCredentialsId') : null
    requestedSonarCredId = requestedSonarCredId?.toString()?.trim()
    def sonarQubeCredId = requestedSonarCredId ?: configuredSonarCredId ?: 'sonarqube-token'
    if (!sonarQubeCredId) {
        error("SonarQube credentials ID could not be resolved for ${repoConfig.repoName}. Set repositoryConfig.sonarQubeCredentialsId or sonarQubeCredentialsId.")
    }
    def sonarCredSource = requestedSonarCredId ? 'pipeline argument' : (configuredSonarCredId ? 'repositoryConfig' : 'default')
    echo "SonarQube 認証情報 ID: ${sonarQubeCredId}（取得元: ${sonarCredSource}）"

    def sonarMavenPluginVersion = cfg.get('sonarMavenPluginVersion', '5.5.0.6356')
    def sonarSkipJreProvisioning = cfg.containsKey('sonarSkipJreProvisioning') ? cfg.get('sonarSkipJreProvisioning').toString().toBoolean() : true
    def sonarVerbose = cfg.containsKey('sonarVerbose') ? cfg.get('sonarVerbose').toString().toBoolean() : true
    def sonarFailFastOnPreflightError = cfg.containsKey('sonarFailFastOnPreflightError') ? cfg.get('sonarFailFastOnPreflightError').toString().toBoolean() : true
    def sonarQubeUrlRaw = cfg.get('sonarQubeUrl', 'http://sonarqube-app-sonarqube.sonarqube.svc.cluster.local:9000')
    def sonarQubeUrl = sonarQubeUrlRaw?.replaceFirst('^hhttp', 'http')
    if (sonarQubeUrl && !sonarQubeUrl.startsWith('http://') && !sonarQubeUrl.startsWith('https://')) {
        echo "⚠️  sonarQubeUrl のスキームが不正です: ${sonarQubeUrl}"
    }
    def sonarProjectName = cfg.get('sonarProjectName', repoConfig.sonarProjectName)
    def sonarBranchSuffix = sanitizeForSonarProjectKey(gitBranch)

    // K8s設定もrepositoryConfigから
    def image = cfg.get('image', repoConfig.k8s.image)
    def cpuReq = cfg.get('cpuRequest', repoConfig.k8s.cpuRequest)
    def memReq = cfg.get('memRequest', repoConfig.k8s.memRequest)
    def cpuLim = cfg.get('cpuLimit', repoConfig.k8s.cpuLimit)
    def memLim = cfg.get('memLimit', repoConfig.k8s.memLimit)

    echo "ビルド設定サマリ: branch=${gitBranch}, profile=${mavenDefaultProfile}, archivePattern=${archivePattern}, skipArchive=${skipArchive}, enableSonarQube=${enableSonarQube}, enableJUnit=${enableJUnit}, enableCoverage=${enableCoverage}, jacocoVersion=${jacocoMavenPluginVersion}, injectJacoco=${injectJacoco}"
    echo "配備設定サマリ: enableRemoteDeploy=${enableRemoteDeploy}, runDeployCommand=${runDeployCommand}, deployArtifactPattern=${deployArtifactPattern}, deployTargetDir=${deployTargetDir}, deployUploadDir=${deployUploadDir}, deployUseSudo=${deployUseSudo}"
    echo "Kubernetes 実行設定: namespace=${k8sNamespace}, image=${image}, cpu=${cpuReq}-${cpuLim}, memory=${memReq}-${memLim}, imagePullSecret=${imagePullSecret}"

    pipeline {
        agent {
            kubernetes {
                namespace k8sNamespace
                defaultContainer 'build'
                yaml k8sPodYaml(
                    image: image,
                    imagePullSecret: imagePullSecret,
                    cpuRequest: cpuReq,
                    memRequest: memReq,
                    cpuLimit: cpuLim,
                    memLimit: memLim
                )
            }
        }

        parameters {
            string(
                name: 'gitBranch',
                defaultValue: 'release1.0.0',
                description: 'Git branch to build (default: release1.0.0)'
            )
            choice(
                name: 'mavenProfile',
                choices: mavenProfileChoices,
                description: 'Maven build profile to use'
            )
            booleanParam(
                name: 'skipTests',
                defaultValue: false,
                description: 'Skip unit tests (default: false)'
            )
            booleanParam(
                name: 'enableSonarQube',
                defaultValue: enableSonarQube,
                description: "Run SonarQube analysis (default: ${enableSonarQube})"
            )
            choice(
                name: 'deployKnownHost',
                choices: deployKnownHostChoices,
                description: 'Remote Deploy 先ホスト。deployUser / SSH credentials はホスト設定から自動解決する'
            )
            booleanParam(
                name: 'enableRemoteDeploy',
                defaultValue: enableRemoteDeploy,
                description: 'JAR / WAR などの成果物を SSH でリモートへ転送する'
            )
            booleanParam(
                name: 'runDeployCommand',
                defaultValue: runDeployCommand,
                description: '転送後に deployCommand を実行して配置・再起動まで行う'
            )
        }

        options {
            // Declarative の自動 Checkout SCM を無効化（Git plugin の known_hosts 問題を回避）
            skipDefaultCheckout(true)

            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
            timeout(time: 30, unit: 'MINUTES')
            timestamps()
        }

        stages {
            stage('Clone GitHub Repo (SSH)') {
                steps {
                    container('build') {
                        script {
                            // パラメータ入力があれば、それを優先
                            def branch = params.gitBranch ?: gitBranch
                            echo "Clone ステージ開始: repo=${gitRepoUrl}, branch=${branch}, dir=repo, knownHost=github.com"
                            gitCloneSsh(
                                repoUrl: gitRepoUrl,
                                branch: branch,
                                dir: 'repo',
                                sshCredentialsId: gitSshCredId,
                                knownHost: 'github.com'
                            )
                        }
                    }
                }
            }

            stage('Maven Build') {
                when {
                    expression { enableSonarQube == false }
                }
                steps {
                    container('build') {
                        dir('repo') {
                            sh """#!/bin/bash
                              set -euo pipefail

                                                            echo "=== 事前確認: repo/.git とオープンハンドル ==="
                              ls -la .git || true
                              lsof | grep repo || true

                                                            echo "=== Maven バージョン ==="
                              mvn -v

                                                            echo "=== ビルド開始: profile=${mavenDefaultProfile} ==="
                                                            echo "実行コマンド: ${mavenCommand} -P ${mavenDefaultProfile} -DskipTests=false"

                              ${mavenCommand} -P "${mavenDefaultProfile}" -DskipTests=false 2>&1 | grep -v "The requested profile" || true
                            """
                        }
                    }
                }
                post {
                    always {
                        script {
                            publishTestsAndCoverageReport(
                                enableJUnit: enableJUnit,
                                enableCoverage: enableCoverage,
                                junitPattern: junitPattern,
                                coveragePattern: coveragePattern
                            )
                        }
                    }
                }
            }

            stage('Maven Build(SonarQube Analysis)') {
                when {
                    expression { enableSonarQube == true }
                }
                steps {
                    container('build') {
                        dir('repo') {
                            // catchError: SonarQube失敗はビルド全体をFAILUREにしない（UNSTABLEに留める）
                            catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                                withCredentials([string(credentialsId: sonarQubeCredId, variable: 'SONAR_TOKEN')]) {
                                    sh """#!/bin/bash
                                      set -eo pipefail

                                                                            echo "=== Maven バージョン ==="
                                      mvn -v

                                                                            echo "=== SonarQube 解析を開始: profile=${mavenDefaultProfile} ==="
                                                                            echo "SonarQube URL: ${sonarQubeUrl}"
                                                                            echo "SonarQube 認証情報 ID: ${sonarQubeCredId}"
                                                                            echo "Sonar Maven プラグインバージョン: ${sonarMavenPluginVersion}"
                                                                            echo "Sonar JRE プロビジョニング無効化: ${sonarSkipJreProvisioning}"
                                                                            echo "Sonar 詳細ログ: ${sonarVerbose}"
                                                                            echo "Sonar 事前確認失敗時に即時終了: ${sonarFailFastOnPreflightError}"

                                      # SONAR_TOKEN の事前検証（-u なしでも明示チェック）
                                      if [ -z "\${SONAR_TOKEN:-}" ]; then
                                                                                echo "ERROR: SONAR_TOKEN が未設定、または空です。"
                                                                                echo "  Jenkins 認証情報 '${sonarQubeCredId}' の Secret Text が有効か確認してください。"
                                        exit 1
                                      fi
                                                                            SONAR_TOKEN_CLEAN="\$(printf '%s' "\${SONAR_TOKEN}" | tr -d '\r\n')"
                                                                            if [ -z "\${SONAR_TOKEN_CLEAN}" ]; then
                                                                                echo "ERROR: SONAR_TOKEN から改行除去後に空文字になりました。"
                                                                                echo "  Jenkins 認証情報 '${sonarQubeCredId}' が空白・改行のみでないか確認してください。"
                                                                                exit 1
                                                                            fi
                                                                            if [ "\${SONAR_TOKEN_CLEAN}" != "\${SONAR_TOKEN}" ]; then
                                                                                echo "警告: SONAR_TOKEN に改行が含まれていたため正規化しました。"
                                                                            fi

                                                                            echo "=== SonarQube 接続性事前確認 ==="
                                                                            SONAR_HTTP_CODE="\$(curl -sS -o /tmp/sonar_server_version.txt -w "%{http_code}" "${sonarQubeUrl}/api/server/version" || true)"
                                                                            SONAR_SERVER_VERSION="\$(cat /tmp/sonar_server_version.txt 2>/dev/null || true)"
                                                                            echo "SonarQube /api/server/version HTTP ステータス: \${SONAR_HTTP_CODE}"
                                                                            if [ -n "\${SONAR_SERVER_VERSION}" ]; then
                                                                                echo "SonarQube サーバーバージョン: \${SONAR_SERVER_VERSION}"
                                                                            fi
                                                                            if [ "\${SONAR_HTTP_CODE}" = "000" ]; then
                                                                                echo "警告: Pod から SonarQube エンドポイントへ到達できません。DNS/ネットワーク/Service URL を確認してください。"
                                                                            fi

                                                                            echo "=== SonarQube トークン事前確認 ==="
                                                                            SONAR_AUTH_HTTP_CODE="\$(curl -sS -o /tmp/sonar_auth_validate.json -w "%{http_code}" -H "Authorization: Bearer \${SONAR_TOKEN_CLEAN}" "${sonarQubeUrl}/api/authentication/validate" || true)"
                                                                            SONAR_AUTH_VALID="false"
                                                                            if [ "\${SONAR_AUTH_HTTP_CODE}" = "200" ] && grep -q '"valid"[[:space:]]*:[[:space:]]*true' /tmp/sonar_auth_validate.json 2>/dev/null; then
                                                                                SONAR_AUTH_VALID="true"
                                                                            fi

                                                                            if [ "\${SONAR_AUTH_VALID}" != "true" ]; then
                                                                                echo "Bearer 認証で有効トークン判定できませんでした（HTTP \${SONAR_AUTH_HTTP_CODE}）。Basic 方式で再試行します。"
                                                                                SONAR_AUTH_HTTP_CODE="\$(curl -sS -o /tmp/sonar_auth_validate.json -w "%{http_code}" -u "\${SONAR_TOKEN_CLEAN}:" "${sonarQubeUrl}/api/authentication/validate" || true)"
                                                                                if [ "\${SONAR_AUTH_HTTP_CODE}" = "200" ] && grep -q '"valid"[[:space:]]*:[[:space:]]*true' /tmp/sonar_auth_validate.json 2>/dev/null; then
                                                                                    SONAR_AUTH_VALID="true"
                                                                                else
                                                                                    SONAR_AUTH_VALID="false"
                                                                                fi
                                                                            fi
                                                                            echo "SonarQube /api/authentication/validate HTTP ステータス: \${SONAR_AUTH_HTTP_CODE}"
                                                                            echo "SonarQube トークン検証結果: \${SONAR_AUTH_VALID}"

                                                                            if [ "\${SONAR_AUTH_HTTP_CODE}" != "200" ] || [ "\${SONAR_AUTH_VALID}" != "true" ]; then
                                                                                echo "ERROR: SonarQube トークン事前確認に失敗しました（HTTP \${SONAR_AUTH_HTTP_CODE}）。"
                                                                                echo "  Jenkins 認証情報 '${sonarQubeCredId}' に、Execute Analysis 権限を持つ有効トークンを設定してください。"
                                                                                if [ "${sonarFailFastOnPreflightError}" = "true" ]; then
                                                                                    exit 1
                                                                                fi
                                                                            fi

                                                                            echo "=== ビルド開始: profile=${mavenDefaultProfile} ==="

                                      PROJECT_NAME="${sonarProjectName}"
                                                                            mvn -e clean ${jacocoPrepareGoal} verify ${jacocoReportGoal} -P "${mavenDefaultProfile}" -DskipTests=false \
                                      org.sonarsource.scanner.maven:sonar-maven-plugin:${sonarMavenPluginVersion}:sonar \
                                                                                -Dsonar.projectKey=\${PROJECT_NAME}_${sonarBranchSuffix} \
                                                                                -Dsonar.projectName=\${PROJECT_NAME}_${sonarBranchSuffix} \
                                        -Dsonar.host.url=${sonarQubeUrl} \
                                                                                -Dsonar.token=\${SONAR_TOKEN_CLEAN} \
                                                                                -Dsonar.verbose=${sonarVerbose} \
                                                                                -Dsonar.scanner.skipJreProvisioning=${sonarSkipJreProvisioning}
                                    """
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        script {
                            publishTestsAndCoverageReport(
                                enableJUnit: enableJUnit,
                                enableCoverage: enableCoverage,
                                junitPattern: junitPattern,
                                coveragePattern: coveragePattern
                            )
                        }
                    }
                    success {
                        echo "✅ SonarQube analysis completed - check results at ${sonarQubeUrl}"
                    }
                    unstable {
                        echo "⚠️ SonarQube analysis failed - build marked UNSTABLE. Check credential '${sonarQubeCredId}', token preflight logs, and scanner bootstrap logs."
                    }
                    failure {
                        echo "⚠️ SonarQube analysis failed - check console logs for details"
                    }
                }
            }

            stage('Archive Artifacts') {
                when {
                    expression { skipArchive == false && archivePattern }
                }
                steps {
                    archiveArtifacts(
                        artifacts: archivePattern,
                        fingerprint: true,
                        allowEmptyArchive: true
                    )
                }
            }

            stage('Remote Deploy') {
                when {
                    expression { params.enableRemoteDeploy }
                }
                steps {
                    container('build') {
                        script {
                            def selectedDeployKnownHost = params.deployKnownHost?.toString()?.trim()
                            if (!selectedDeployKnownHost || selectedDeployKnownHost == 'not-configured') {
                                error('deployKnownHost is not configured. Define deployHostConfigs in repositoryConfig.groovy or pipeline cfg.')
                            }

                            def selectedDeployConfig = deployHostConfigs[selectedDeployKnownHost]
                            if (!(selectedDeployConfig instanceof Map) || selectedDeployConfig.isEmpty()) {
                                error("No deploy host configuration found for selected host: ${selectedDeployKnownHost}")
                            }

                            def resolvedDeployHost = (
                                selectedDeployConfig.get('deployHost')
                                    ?: selectedDeployConfig.get('host')
                                    ?: selectedDeployKnownHost
                            )?.toString()?.trim()
                            def resolvedDeployKnownHost = (
                                selectedDeployConfig.get('deployKnownHost')
                                    ?: selectedDeployConfig.get('knownHost')
                                    ?: selectedDeployKnownHost
                            )?.toString()?.trim()
                            def resolvedDeployUser = (
                                selectedDeployConfig.get('deployUser')
                                    ?: selectedDeployConfig.get('user')
                            )?.toString()?.trim()
                            def resolvedDeploySshCredId = (
                                selectedDeployConfig.get('deploySshCredentialsId')
                                    ?: selectedDeployConfig.get('sshCredentialsId')
                                    ?: selectedDeployConfig.get('credentialsId')
                            )?.toString()?.trim()
                            def resolvedDeployPortRaw = (
                                selectedDeployConfig.get('deployPort')
                                    ?: selectedDeployConfig.get('port')
                            )

                            if (!resolvedDeployHost) {
                                error("deployHost could not be resolved for selected host: ${selectedDeployKnownHost}")
                            }
                            if (!resolvedDeployKnownHost) {
                                error("deployKnownHost could not be resolved for selected host: ${selectedDeployKnownHost}")
                            }
                            if (!resolvedDeployUser) {
                                error("deployUser could not be resolved for selected host: ${selectedDeployKnownHost}")
                            }
                            if (!resolvedDeploySshCredId) {
                                error("deploySshCredentialsId could not be resolved for selected host: ${selectedDeployKnownHost}")
                            }
                            if (resolvedDeployPortRaw == null || resolvedDeployPortRaw.toString().trim() == '') {
                                error("deployPort could not be resolved for selected host: ${selectedDeployKnownHost}")
                            }
                            def resolvedDeployPort = (resolvedDeployPortRaw as Integer)

                            if (!deployArtifactPattern) {
                                error('deployArtifactPattern is required when enableRemoteDeploy=true')
                            }

                            def matchedArtifacts = findFiles(glob: deployArtifactPattern)
                                .findAll { !it.directory }
                                .collect { it.path }
                                .sort()

                            if (!matchedArtifacts) {
                                error("No deployment artifacts matched pattern: ${deployArtifactPattern}")
                            }

                            def remoteArtifactPaths = matchedArtifacts.collect { path ->
                                "${deployUploadDir}/${path.tokenize('/').last()}"
                            }

                            echo "リモート配備先を解決しました: knownHost=${resolvedDeployKnownHost}, host=${resolvedDeployHost}, user=${resolvedDeployUser}, port=${resolvedDeployPort}"
                            echo "リモート配備先ディレクトリ: target=${deployTargetDir}, upload=${deployUploadDir}, artifactPattern=${deployArtifactPattern}"
                            echo "配備対象成果物数: ${matchedArtifacts.size()}"
                            matchedArtifacts.eachWithIndex { artifactPath, idx ->
                                echo "  [${idx + 1}] ${artifactPath}"
                            }
                            echo "リモート上の成果物パス: ${remoteArtifactPaths.join(', ')}"

                            // `deployTargetDir` は最終的な配置先、`deployUploadDir` は scp 用の一時配置先として扱う。
                            // root 配下へ直接 scp できない環境でも、staging 後に deployCommand で sudo 配置できるようにする。
                            remoteSsh(
                                host: resolvedDeployHost,
                                user: resolvedDeployUser,
                                sshCredentialsId: resolvedDeploySshCredId,
                                port: resolvedDeployPort,
                                knownHost: resolvedDeployKnownHost,
                                strictHostKeyChecking: true,
                                command: "mkdir -p ${shellQuote(deployUploadDir)}"
                            )

                                                        echo "scp で成果物をアップロードします（knownHost=${resolvedDeployKnownHost}, port=${resolvedDeployPort}）"
                                                        sshagent(credentials: [resolvedDeploySshCredId]) {
                                sh """#!/bin/bash
                                  set -euo pipefail

                                  command -v scp >/dev/null 2>&1 || {
                                                                        echo 'ERROR: build コンテナに scp コマンドが見つかりません'
                                    exit 1
                                  }

                                  mkdir -p ~/.ssh
                                  chmod 700 ~/.ssh
                                  touch ~/.ssh/known_hosts
                                  chmod 600 ~/.ssh/known_hosts

                                  ssh-keyscan -p ${resolvedDeployPort} -t rsa,ecdsa,ed25519 -H ${shellQuote(resolvedDeployKnownHost)} >> ~/.ssh/known_hosts 2>/dev/null || true
                                  sort -u ~/.ssh/known_hosts -o ~/.ssh/known_hosts || true

                                  SCP_OPTIONS=(
                                    -o BatchMode=yes
                                    -o ConnectTimeout=10
                                    -o StrictHostKeyChecking=yes
                                    -o UserKnownHostsFile=\"\$HOME/.ssh/known_hosts\"
                                    -P ${resolvedDeployPort}
                                  )

                                  for artifact in ${matchedArtifacts.collect { shellQuote(it) }.join(' ')}; do
                                                                        echo "アップロード開始: \$artifact -> ${resolvedDeployUser}@${resolvedDeployHost}:${deployUploadDir}/"
                                    scp "\${SCP_OPTIONS[@]}" "\$artifact" ${shellQuote("${resolvedDeployUser}@${resolvedDeployHost}:${deployUploadDir}/")}
                                                                        echo "アップロード成功: \$artifact"
                                  done
                                """
                            }

                                                        echo "アップロード結果をリモートで確認します（ll 優先、未対応時は ls -l）"
                                                        remoteSsh(
                                                                host: resolvedDeployHost,
                                                                user: resolvedDeployUser,
                                                                sshCredentialsId: resolvedDeploySshCredId,
                                                                port: resolvedDeployPort,
                                                                knownHost: resolvedDeployKnownHost,
                                                                strictHostKeyChecking: true,
                                                                command: """
if command -v ll >/dev/null 2>&1; then
    echo 'リモート一覧表示: ll を実行します'
    ll ${shellQuote(deployUploadDir)}
else
    echo 'リモート一覧表示: ll がないため ls -l を実行します'
    ls -l ${shellQuote(deployUploadDir)}
fi
""".trim()
                                                        )

                            if (deployCommand?.trim() && params.runDeployCommand) {
                                                                echo "deployCommand 実行を開始します（sudo=${deployUseSudo}）"
                                def remoteDeployCommand = """
export DEPLOY_TARGET_DIR=${shellQuote(deployTargetDir)}
export DEPLOY_UPLOAD_DIR=${shellQuote(deployUploadDir)}
export DEPLOY_ARTIFACT_PATHS=${shellQuote(remoteArtifactPaths.join('\n'))}
export DEPLOY_FIRST_ARTIFACT=${shellQuote(remoteArtifactPaths.first())}

${deployCommand}
""".trim()

                                remoteSsh(
                                    host: resolvedDeployHost,
                                    user: resolvedDeployUser,
                                    sshCredentialsId: resolvedDeploySshCredId,
                                    port: resolvedDeployPort,
                                    knownHost: resolvedDeployKnownHost,
                                    useSudo: deployUseSudo,
                                    strictHostKeyChecking: true,
                                    command: remoteDeployCommand
                                )
                                echo 'deployCommand 実行が完了しました。'
                            } else if (deployCommand?.trim()) {
                                echo 'runDeployCommand=false のため、成果物転送のみ実行しました。'
                            } else {
                                echo 'deployCommand が未設定のため、成果物転送のみ実行しました。'
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "✅ ビルド成功（profile: ${mavenDefaultProfile}）"
            }
            unstable {
                echo "⚠️ ビルド不安定（profile: ${mavenDefaultProfile}）: SonarQube 結果と認証情報を確認してください"
            }
            failure {
                echo "❌ ビルド失敗: 詳細はコンソールログを確認してください"
            }
            cleanup {
                script {
                    // Pod 起動失敗/Checkout前失敗などで agent(workspace) が無い場合はスキップ
                    if (!env.NODE_NAME || !env.WORKSPACE) {
                        echo "🧹 クリーンアップをスキップ: agent/workspace が利用不可です（NODE_NAME=${env.NODE_NAME}, WORKSPACE=${env.WORKSPACE}）"
                        return
                    }

                    container('build') {
                        echo '🧹 ワークスペースの権限調整を実行します。'
                        // soften permissions/ownership so workspace cleanup can proceed
                        sh '''#!/bin/bash
                          set -euo pipefail
                          chown -R "$(id -u)":"$(id -g)" . || true
                          chmod -R u+rwX . || true
                        '''
                        try {
                            echo '🧹 deleteDir() でワークスペースを削除します。'
                            deleteDir()
                        } catch (err) {
                            echo "deleteDir() に失敗したため rm -rf へフォールバックします: ${err}"
                            sh '''#!/bin/bash
                              set -euo pipefail
                              rm -rf -- ./* ./.??* || true
                            '''
                        }
                    }
                }
            }
        }
    }
}

/**
 * シェル引数として安全に埋め込めるよう、値を単一引用符でエスケープする。
 */
private String shellQuote(String value) {
    return "'${(value ?: '').replace("'", "'\"'\"'")}'"
}

/**
 * SonarQube の projectKey として利用できるよう、ブランチ名を安全な文字列へ正規化する。
 */
private String sanitizeForPathSegment(String value) {
    def sanitized = (value ?: 'app').replaceAll('[^A-Za-z0-9_.-]', '-')
    return sanitized ?: 'app'
}

private String sanitizeForSonarProjectKey(String value) {
    def sanitized = (value ?: 'main').replaceAll('[^A-Za-z0-9_.:-]', '_')
    return sanitized ?: 'main'
}

/**
 * JUnit / JaCoCo の集計を行い、コンソールに Markdown 表で出力しつつ
 * Jenkins UI 用に `junit` / `recordCoverage` を呼び、ビルド説明欄にも要約を貼る。
 *
 * - `junit` ステップ: 標準のテスト結果 UI に流す
 * - `recordCoverage` ステップ: Coverage Plugin が入っていればトレンドグラフを生成する
 * - Markdown 表: コンソールログから Slack/Qiita などへ転記しやすくする
 * - currentBuild.description: ジョブ一覧から一目で Tests / LineCov を確認できるようにする
 *
 * 集計部はシェルで実装している。Pipeline (CPS) 上で巨大 XML を Groovy 正規表現
 * イテレータで処理すると sandbox/CPS 周りで詰まりやすいため、
 * パース・集計はサブプロセス側に寄せ、Groovy 側は表示と公開ステップに専念する。
 */
def publishTestsAndCoverageReport(Map opts = [:]) {
    def enableJUnit = opts.containsKey('enableJUnit') ? opts.get('enableJUnit').toString().toBoolean() : true
    def enableCoverage = opts.containsKey('enableCoverage') ? opts.get('enableCoverage').toString().toBoolean() : true
    def junitPattern = opts.get('junitPattern', '**/target/surefire-reports/*.xml')?.toString()?.trim()
    def coveragePattern = opts.get('coveragePattern', '**/target/site/jacoco/jacoco.xml')?.toString()?.trim()

    catchError(buildResult: currentBuild.currentResult, stageResult: 'SUCCESS') {
        // --- シェルで集計し、Markdown 表 + 機械可読な要約行を stdout に出す ---
        def summaryOutput = ''
        try {
            summaryOutput = sh(returnStdout: true, script: '''#!/bin/bash
set -uo pipefail

# Surefire / Failsafe の testsuite 属性を合算する
sf_tests=0; sf_failures=0; sf_errors=0; sf_skipped=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  # 1ファイルにつき先頭の <testsuite ...> 要素のみを参照する
  head=$(grep -oE '<testsuite[^>]*>' "$f" | head -1 || true)
  [ -z "$head" ] && continue
  t=$(printf '%s' "$head" | grep -oE 'tests="[0-9]+"' | head -1 | grep -oE '[0-9]+' || echo 0)
  fa=$(printf '%s' "$head" | grep -oE 'failures="[0-9]+"' | head -1 | grep -oE '[0-9]+' || echo 0)
  e=$(printf '%s' "$head" | grep -oE 'errors="[0-9]+"' | head -1 | grep -oE '[0-9]+' || echo 0)
  s=$(printf '%s' "$head" | grep -oE 'skipped="[0-9]+"' | head -1 | grep -oE '[0-9]+' || echo 0)
  sf_tests=$((sf_tests + ${t:-0}))
  sf_failures=$((sf_failures + ${fa:-0}))
  sf_errors=$((sf_errors + ${e:-0}))
  sf_skipped=$((sf_skipped + ${s:-0}))
done < <(find . \\( -path '*/target/surefire-reports/TEST-*.xml' -o -path '*/target/failsafe-reports/TEST-*.xml' \\) -type f 2>/dev/null)

# JaCoCo: 各 jacoco.xml の最後の <counter type=...> がレポート全体の集計値
declare -A jc_missed
declare -A jc_covered
for type in INSTRUCTION BRANCH LINE COMPLEXITY METHOD CLASS; do
  jc_missed[$type]=0
  jc_covered[$type]=0
done

jacoco_files=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  jacoco_files=$((jacoco_files + 1))
  for type in INSTRUCTION BRANCH LINE COMPLEXITY METHOD CLASS; do
    last=$(grep -oE "<counter type=\\"$type\\" missed=\\"[0-9]+\\" covered=\\"[0-9]+\\"" "$f" | tail -1 || true)
    if [ -n "$last" ]; then
      m=$(printf '%s' "$last" | grep -oE 'missed="[0-9]+"' | grep -oE '[0-9]+' || echo 0)
      c=$(printf '%s' "$last" | grep -oE 'covered="[0-9]+"' | grep -oE '[0-9]+' || echo 0)
      jc_missed[$type]=$((jc_missed[$type] + ${m:-0}))
      jc_covered[$type]=$((jc_covered[$type] + ${c:-0}))
    fi
  done
done < <(find . -path '*/target/site/jacoco/jacoco.xml' -type f 2>/dev/null)

passed=$((sf_tests - sf_failures - sf_errors - sf_skipped))

echo ""
echo "=== Test & Coverage Summary ==="
echo ""
echo "#### JUnit (Surefire/Failsafe)"
echo ""
echo "| Tests | Passed | Failures | Errors | Skipped |"
echo "|------:|-------:|---------:|-------:|--------:|"
echo "| $sf_tests | $passed | $sf_failures | $sf_errors | $sf_skipped |"
echo ""
echo "#### Coverage (JaCoCo)"
echo ""
if [ "$jacoco_files" -gt 0 ]; then
  echo "| Metric      | Covered | Missed | Total | Coverage |"
  echo "|:------------|--------:|-------:|------:|---------:|"
  for type in INSTRUCTION BRANCH LINE METHOD CLASS COMPLEXITY; do
    m=${jc_missed[$type]}
    c=${jc_covered[$type]}
    total=$((m + c))
    if [ "$total" -gt 0 ]; then
      pct=$(awk -v c="$c" -v t="$total" 'BEGIN { printf "%.2f%%", (c*100.0)/t }')
    else
      pct="N/A"
    fi
    printf "| %-11s | %7d | %6d | %5d | %8s |\\n" "$type" "$c" "$m" "$total" "$pct"
  done
else
  echo "_jacoco.xml が見つかりませんでした。pom.xml に jacoco-maven-plugin の設定があるか、CLI 注入が成功しているか確認してください。_"
fi

# 機械可読サマリ（Groovy 側で description に使う）
m=${jc_missed[LINE]}
c=${jc_covered[LINE]}
total=$((m + c))
if [ "$total" -gt 0 ]; then
  line_pct=$(awk -v c="$c" -v t="$total" 'BEGIN { printf "%.1f%%", (c*100.0)/t }')
else
  line_pct="N/A"
fi
echo ""
echo "TEST_COVERAGE_SUMMARY tests=$sf_tests passed=$passed failures=$sf_failures errors=$sf_errors skipped=$sf_skipped line_coverage=$line_pct jacoco_files=$jacoco_files"
''')
        } catch (shErr) {
            echo "Test/Coverage 集計シェルでエラー: ${shErr.message}"
        }

        if (summaryOutput) {
            echo summaryOutput
        }

        // --- Jenkins UI: JUnit ---
        if (enableJUnit) {
            try {
                junit testResults: junitPattern, allowEmptyResults: true, skipPublishingChecks: true
            } catch (junitErr) {
                echo "junit ステップでエラー: ${junitErr.message}"
            }
        }

        // --- Jenkins UI: Coverage Plugin ---
        if (enableCoverage) {
            try {
                // jacoco.xml の配置からモジュール単位の src/main/java を逆算する。
                // 例: repo/module-a/target/site/jacoco/jacoco.xml -> repo/module-a/src/main/java
                // Source 解決先を明示しないと、Coverage Plugin が workspace 直下の
                // src/main/java 固定で探索し、マルチモジュール構成で not found が多発する。
                def coverageSourceDirectories = findFiles(glob: coveragePattern)
                    .findAll { !it.directory }
                    .collect { it.path.replaceAll('/target/site/jacoco/jacoco\\.xml$', '/src/main/java') }
                    .findAll { sourceDir -> fileExists(sourceDir) }
                    .unique()
                    .sort()

                if (coverageSourceDirectories) {
                    echo "Coverage ソース探索ディレクトリを明示します: ${coverageSourceDirectories.join(', ')}"
                } else {
                    echo "⚠️ Coverage ソース探索ディレクトリを解決できませんでした。coveragePattern=${coveragePattern}"
                }

                def recordCoverageArgs = [
                    tools: [[parser: 'JACOCO', pattern: coveragePattern]],
                    sourceCodeRetention: 'EVERY_BUILD'
                ]
                if (coverageSourceDirectories) {
                    recordCoverageArgs.sourceDirectories = coverageSourceDirectories.collect { [path: it] }
                }

                recordCoverage(recordCoverageArgs)
            } catch (recErr) {
                echo "recordCoverage 呼び出しに失敗: ${recErr.message}（Coverage Plugin が未導入の可能性。コンソールの Markdown 表で代替確認してください）"
            }
        }

        // --- ビルド説明欄に1行サマリを追記 ---
        try {
            def line = summaryOutput ? summaryOutput.readLines().reverse().find { it?.startsWith('TEST_COVERAGE_SUMMARY ') } : null
            if (line) {
                def fields = [:]
                line.replaceFirst('TEST_COVERAGE_SUMMARY ', '').tokenize(' ').each { kv ->
                    def parts = kv.split('=', 2)
                    if (parts.length == 2) { fields[parts[0]] = parts[1] }
                }
                def fails = ((fields['failures'] ?: '0') as int) + ((fields['errors'] ?: '0') as int)
                def desc = "Tests: ${fields['tests'] ?: '?'} (NG=${fails}, Skip=${fields['skipped'] ?: '?'}) / LineCov: ${fields['line_coverage'] ?: 'N/A'}"
                currentBuild.description = (currentBuild.description ? "${currentBuild.description} | ${desc}" : desc)
            }
        } catch (descErr) {
            echo "currentBuild.description 更新に失敗: ${descErr.message}"
        }
    }
}