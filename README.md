# 🚀 Omvexis Jenkins CI/CD Pipeline: `cs_service_builder`

This Jenkins pipeline automates the build, test, security scan, and deployment process for microservices under the **Omvexis** ecosystem. It is designed to run on a Kubernetes-based Jenkins setup using dynamic agents.

---

## 📌 Key Features

- ✅ Supports both **commercial** and **federal** Docker image builds  
- ✅ Dynamically authenticates and scans with **Snyk** for SAST and SCA vulnerabilities  
- ✅ Runs **unit tests** inside a Python virtual environment  
- ✅ Performs **container image scanning** using **Trivy**  
- ✅ Deploys Docker images to selected Kubernetes clusters  
- ✅ Integrates with Azure Git repos and supports branch-specific builds  
- ✅ Sends notifications on failure via Office365  

---

## 🛠️ Pipeline Breakdown

### 1. **Trigger & Initialization**
- Identifies the user who triggered the job.
- Sets up environment variables and configures Docker registries based on the selected cluster.

---

### 2. **Pipeline Parameters**

| Parameter           | Description                                                                 |
|---------------------|-----------------------------------------------------------------------------|
| `Docker_Build_Type` | Choose `federal` for IronBank compliance; `commercial` for default Dockerfile |
| `BranchName`        | Branch name to be cloned from Azure DevOps                                   |
| `Deployment`        | Whether to deploy the image after building                                   |
| `Script_Name`       | Optional: Comma-separated list of scripts to run (e.g. `migrate.py`)        |
| `Repo_Name`         | Select the service to build (`hfnlife_ui`, `hfnlife_products`, etc.)         |
| `Cluster_Type`      | Target Kubernetes cluster (`dev`, `prod-eu`)                                 |

---

### 3. **Stages Overview**

#### 🔹 Clone and Checkout
- Clones the specified repo and branch from Azure DevOps.

#### 🔹 Static Analysis (Parallel)
- **Unit Tests**: Executes `pytest` inside a Python 3.9 virtual environment.
- **SCA (Open Source Scan)**: Uses Snyk to scan for vulnerabilities in dependencies.
- **SAST (Code Scan)**: Runs Snyk static code analysis.

#### 🔹 Template Config Check
- Validates existence of `template.conf`.
- Base64 encodes the file for labeling Docker image.

#### 🔹 Build & Push Docker Image
- Logs into the container registry.
- Builds image using either `Dockerfile` or `Dockerfile.ironbank`.
- Labels image with commit ID and encoded config.
- Pushes to the configured Docker registry.

#### 🔹 Image Vulnerability Scan
- Uses Trivy to scan for vulnerabilities in the built image.
- Fails the pipeline if critical issues are found.

#### 🔹 Optional Deployment
- If enabled, triggers the `omvexis_service_deployer` job with parameters.

---

## 📤 Post-build Actions

- Sends a failure alert using Office365 webhook with build context.

---

## 🧱 Prerequisites

- Jenkins running with Kubernetes agents
- Pre-configured Jenkins credentials:
  - `jenkins_ops_lib` for Git
  - `snyk_api_token` for Snyk auth
  - Docker registry creds (`Registry_Cred`)
- Installed on agent:
  - Python 3.9
  - Snyk CLI
  - Trivy
- Required Jenkins shared libraries and `libraryResource` assets:
  - `default-agents-pod.yaml`
  - `cluster.json`

---

## 📎 Related Jobs

- [`omvexis_service_deployer`](https://omvexis.site/job/omvexis_service_deployer/) — downstream job for image deployment

---

## 🧑‍💻 Maintainers

For any issues with this pipeline, please contact the DevOps team at `devops@omvexis.com`.

