@Library('gbif-common-jenkins-pipelines') _

pipeline {
    agent any
    tools {
      maven 'Maven 3.9.9'
      jdk 'LibericaJDK21'
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        skipDefaultCheckout(true)   // disables auto checkout - we wipe the workspace here
        skipStagesAfterUnstable()
        timestamps()
        ansiColor('xterm')
    }
    parameters {
        separator(name: "release_separator", sectionHeader: "Release Parameters")
        booleanParam(name: 'RELEASE', defaultValue: false, description: 'Do a Maven release')
        string(name: 'RELEASE_VERSION', defaultValue: '', description: 'Release version (optional)')
        string(name: 'DEVELOPMENT_VERSION', defaultValue: '', description: 'Development version (optional)')
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()             // clean workspace
                checkout scm            // fresh clone
            }
        }

        stage('Maven build') {
            when {
                allOf {
                    not { expression { params.RELEASE } };
                }
            }
            steps {
                withMaven(
                        globalMavenSettingsConfig: 'org.jenkinsci.plugins.configfiles.maven.GlobalMavenSettingsConfig1387378707709',
                        mavenOpts: '-Xmx2048m -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS',
                        mavenSettingsConfig: 'b043019e-79d8-48fd-8ecf-b20e3fb0a3cc',
                        traceability: true
                ) {
                    sh '''mvn clean -U -T 4 deploy'''
                }
            }
        }

        stage('Maven release: Main project') {
            when {
                allOf {
                    expression { params.RELEASE };
                    branch 'master';
                }
            }
            steps {
                script {
                    def releaseArgs = utils.createReleaseArgs(params.RELEASE_VERSION, params.DEVELOPMENT_VERSION, false)
                    configFileProvider(
                            [configFile(fileId: 'org.jenkinsci.plugins.configfiles.maven.GlobalMavenSettingsConfig1387378707709',
                                    variable: 'MAVEN_SETTINGS_XML')]) {
                        git 'https://github.com/gbif/matching-ws.git'
                        sh "mvn -s \$MAVEN_SETTINGS_XML -B -Denforcer.skip=true -Darguments=\"-DskipTests -DskipITs\" release:prepare release:perform -Dtag=v${params.RELEASE_VERSION} ${releaseArgs}"
                    }
                }
            }
        }
    }

    // 🔔 Email notifications
    post {
        failure {
            echo 'Pipeline execution failed!'
            emailext(
                to: 'dmartin@gbif.org',
                subject: "matching-ws build FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                body: """\
                    Build failed!

                    Project: ${env.JOB_NAME}
                    Build Number: ${env.BUILD_NUMBER}
                    URL: ${env.BUILD_URL}

                    Please check the Jenkins logs for details.
                    """
            )
        }
        success {
            echo 'Pipeline executed successfully!'
        }
    }

}
