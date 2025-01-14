properties([pipelineTriggers([githubPush()])])
node {
    jenkinsGitCredentials = 'jenkinsGitHubSvc'
    git url: 'https://github.com/Demonslyr/text-generation-webui.git',branch: 'atriarch',credentialsId: jenkinsGitCredentials
    stage('setup'){
        //checkout scm
        currentBuild.description = "${Branch}"
        appName = "${ImageName}"
        nugetCredId = "${NugetCredentials}"
        dockerRepo = "${DockerRepo}"
        dockerCredId = "${DockerCredentials}"
        dockerfilePathFromRoot = "${DockerFilePathAndFilename}"// this is the path from the base directory
        fullImageName = "${dockerRepo}/${appName}:v1.0.${BUILD_NUMBER}"
        gitOpsRepo = "${GitOpsRepo}"
        gitOpsBranch = "${GitOpsBranch}"
        pathToDeploymentYaml = "${PathToDeploymentYaml}"
    }
    stage('test'){
        // def results = sh returnStdout: true, script: "dotnet test "
    }
    stage('build'){
        withCredentials([usernamePassword(usernameVariable: "NUGET_USER",passwordVariable: "NUGET_PASS", credentialsId: nugetCredId)]){
           def buildout = sh(returnStdout: true, script: "docker build --build-arg NUGET_USER=${NUGET_USER} --build-arg NUGET_PASS=${NUGET_PASS} -t ${appName} -f ${dockerfilePathFromRoot} .")
           println buildout
        }
    }
    stage('push'){
        def tagout = sh(returnStdout: true, script: "docker tag ${appName} ${fullImageName}")
        println tagout
        withCredentials([usernamePassword(usernameVariable: "DOCKER_USER",passwordVariable: "DOCKER_PASS", credentialsId: dockerCredId)]){
            def loginout = sh(returnStdout: true, script: "echo ${DOCKER_PASS} | docker login ${dockerRepo} --username ${DOCKER_USER} --password-stdin")
            println loginout
            def pushout = sh(returnStdout: true, script: "docker push ${fullImageName}")
            println pushout
        }
    }
    stage('updateGitOpsRepo'){
        dir('GitOps') { // Clone the repo in a new workspace to avoid conflicts
            git url: "https://github.com/${gitOpsRepo}", branch: gitOpsBranch, credentialsId: jenkinsGitCredentials, changelog: false, poll: false
            writeFile file: 'update_yaml.py', text: """
import yaml
import sys

documents = []
foundImage = False
with open('${pathToDeploymentYaml}', 'r') as file:
    for doc in yaml.safe_load_all(file):
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == '${appName}':
            for container in doc['spec']['template']['spec']['containers']:
                if 'image' in container:
                    container['image'] = '${fullImageName}'
                    foundImage = True                    
        documents.append(doc)

print(f'Found image: {foundImage}')
print(f'documents: {documents}' if foundImage else 'No documents found')

with open('${pathToDeploymentYaml}', 'w') as file:
    yaml.safe_dump_all(documents, file, explicit_start=True)
"""
            sh 'python3 update_yaml.py'
            sh "git add ${pathToDeploymentYaml}"
            sh "git commit -m \"Update ${pathToDeploymentYaml} to ${fullImageName}\""
            withCredentials([usernamePassword(usernameVariable: 'GIT_CRED_USER', passwordVariable: 'GIT_CRED_PASS', credentialsId: jenkinsGitCredentials)]) {
                // Why is usernameVariable not working?
                sh "git push https://Demonslyr:${GIT_CRED_PASS}@github.com/${gitOpsRepo} ${gitOpsBranch}"
            }
        }
    }
}
