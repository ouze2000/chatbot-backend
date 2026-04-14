pipeline {
    agent any

    environment {
        DEPLOY_DIR = '/var/jenkins_home/workspace/chatbot'
    }

    stages {
        stage('Checkout Backend') {
            steps {
                dir("${DEPLOY_DIR}/chatbot-backend") {
                    git url: 'https://github.com/ouze2000/chatbot-backend.git', branch: 'main'
                }
            }
        }

        stage('Checkout Frontend') {
            steps {
                dir("${DEPLOY_DIR}/chatbot-frontend") {
                    git url: 'https://github.com/ouze2000/chatbot-frontend.git', branch: 'main'
                }
            }
        }

        stage('Deploy') {
            steps {
                dir("${DEPLOY_DIR}/chatbot-backend") {
                    sh 'docker-compose up --build -d'
                }
            }
        }
    }

    post {
        success {
            echo '배포 성공'
        }
        failure {
            echo '배포 실패'
        }
    }
}
