#!/usr/bin/env groovy
package com.omvexis.devops

//Below Code is used to create dir inside a git repo mostly will be used for azure repo and k8s cluster
def dirCreation(dirName)
{
  sh 'pwd'
  sh "mkdir -p $dirName"
  sh 'ls -l'
}
def runResourceScript(workspace, resourcePath, option, args) {
    String request = libraryResource resourcePath
    env.fileName = workspace + "/script_" + System.currentTimeMillis() + ".sh"
    writeFile file: fileName, text: request
    sh "chmod +x ${fileName}"
    env.Option = option
    env.Args = args
    def cmd = fileName + " " + option + " " + args
    env.Return_Value = sh(script: 'sh ${fileName} ${Option} ${Args}', returnStdout: true)
    sh "rm -f ${fileName}"
}
def checkFileExists(path){
  if(!fileExists(path)){
    echo "File path is ${path}"
    error("unable to find file ${path}")
  }
}

def runResourcePythonScript(workspace, resourcePath, args) {
    String request = libraryResource resourcePath
    def fileName = workspace + "/script_" + System.currentTimeMillis() + ".py"
    writeFile file: fileName, text: request

    def cmd = "/usr/local/bin/python3.9 " + fileName + " " + args
    echo "Executing: ${cmd}"
    sh cmd
}


def runPythonScriptInPwd(fileName, args) {
    def cmd = "/usr/bin/python3.9 " + fileName + " " + args
    echo "Executing: ${cmd}"
    sh cmd
}

def checkoutServiceSource(gitRepo, gitBranch, gitCredId) {

    opsCheckout(this){
        branchName = gitBranch
        credentialsName = gitCredId
        githubRepositoryURL = "https://" + gitRepo
    }

    sh """
        pwd
        git checkout ${gitBranch}
    """

}

//Below function is used to connect a server to deploy docker containers.
def csDeployer(Service_Name,Tag_No,Helm_Repo_Url,Cluster_Type)
{
    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'whizgendeployer', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
    
     sh """
        export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
        export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
        """
    echo "###### Fetching kube-config file ######"
    sh "aws eks update-kubeconfig --name ${Cluster_Type} --region ap-south-1"
    sh "kubectl get ns"
    def exists = sh(script: "helm status ${Service_Name} -n ${Service_Name} > /dev/null 2>&1",returnStatus: true
    )

    if (exists == 0) {
        echo " Helm Update ${Service_Name} in-progress."
        sh "helm upgrade ${Service_Name} ${Helm_Repo_Url}/${Service_Name} --version ${Tag_No} -n ${Service_Name}"

    } else {
        echo " Helm Install ${Service_Name} in-progress "
        sh "helm install ${Service_Name} ${Helm_Repo_Url}/${Service_Name} --version ${Tag_No} -n ${Service_Name}"
    }
  }
}
//Below command used to fetch docker image to get label section
def csDockerImageLabelFetch(RegistryUrl,RegistryCred,ServiceName,DockerBuildType,ClusterType,Tag_No)
{
        sh "docker pull ${RegistryUrl}/${ServiceName}_${DockerBuildType}_${ClusterType}:${Tag_No}"
        echo "######### Inprogess: Fetch Label ###########"
        env.EncryptedTemplate= sh(script: "docker inspect ${RegistryUrl}/${ServiceName}_${DockerBuildType}_${ClusterType}:${Tag_No} | jq -r '.[0].Config.Labels.cs_template'",returnStdout: true).trim()
        echo "########  Completed: Fetch Label #############"    
}


//Below Function is used to decode base64
def base64_decode(filename,templateconfname)
{
  //sh "base64 --decode ${filename} > ${templateconfname}"
  sh "set -x"
  sh "echo ${filename} | base64 --decode > ${templateconfname}"
  sh "set +x"
  sh "ls -l"
  sh "pwd"
}
// Encode files like template.conf

def base64_encode(filename)
{
  def encoded_file = sh(script: "cat ${filename}  | base64 -w 0", returnStdout: true).trim()
  return encoded_file
}

def yaml_dependency_repo_read(filename,reponame)
{
   env.Filename = filename
   echo "${Filename}"
   YamlContent = libraryResource("scripts/${Filename}")
   writeFile(file: "${Filename}", text: YamlContent)
   def configVal = readYaml file: "${Filename}"
   def repo_section=configVal["${reponame}"]
   return "${repo_section}"
   
}


// Used in service-builder
def dockerbuild(registryUrl,registryCred,dockerBuildType,dockerFile,repo_Name,timeStamp,labels,clusterType){
  git_commit = labels.cs_gitcommit
  encoded_conf = labels.cs_template
  docker_build_cmd = """docker build --progress plain -t ${registryUrl}/${repo_Name}_${dockerBuildType}_${clusterType}:${timeStamp} --pull --label cs_gitcommit=${git_commit} --label cs_template=${encoded_conf} -f ${dockerFile} . """
  
  sh """
       ${docker_build_cmd}
       docker push ${registryUrl}/${repo_Name}_${dockerBuildType}_${clusterType}:${timeStamp}
  """
}

// Used to login to container registry
def dockerLogin(registryURL,registryCredID){
   withCredentials([usernamePassword(credentialsId: "$registryCredID", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
        echo "###### Container registry login in-progress ######"
        sh "docker login ${registryURL} -u $USERNAME -p $PASSWORD"
   }
}

