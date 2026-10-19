# Build 2 image va day len Amazon ECR.
# Can: Docker Desktop dang chay + AWS CLI da "aws configure".
# Chay tu thu muc goc du an:
#   .\deploy\push-ecr.ps1                 (tag = latest)
#   .\deploy\push-ecr.ps1 -Tag 1.0.1
param(
    [string]$Account = "482762106946",
    [string]$Region  = "ap-southeast-2",
    [string]$Tag     = "latest"
)
$ErrorActionPreference = "Stop"
$Registry = "$Account.dkr.ecr.$Region.amazonaws.com"

Write-Host "== Dang nhap ECR $Registry" -ForegroundColor Cyan
aws ecr get-login-password --region $Region | docker login --username AWS --password-stdin $Registry
if ($LASTEXITCODE -ne 0) { throw "Dang nhap ECR that bai. Kiem tra 'aws configure'." }

foreach ($svc in @("backend", "frontend")) {
    $repo = "adaptive-$svc"

    aws ecr describe-repositories --repository-names $repo --region $Region *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "== Tao repository $repo" -ForegroundColor Cyan
        aws ecr create-repository --repository-name $repo --region $Region | Out-Null
    }

    Write-Host "== Build $repo`:$Tag" -ForegroundColor Cyan
    # linux/amd64 khop voi runtimePlatform X86_64 trong task definition
    docker build --platform linux/amd64 -t "$Registry/$repo`:$Tag" "./$svc"
    if ($LASTEXITCODE -ne 0) { throw "Build $svc that bai" }

    Write-Host "== Push $repo`:$Tag" -ForegroundColor Cyan
    docker push "$Registry/$repo`:$Tag"
    if ($LASTEXITCODE -ne 0) { throw "Push $svc that bai" }
}

Write-Host "Xong:" -ForegroundColor Green
Write-Host "  $Registry/adaptive-backend`:$Tag"
Write-Host "  $Registry/adaptive-frontend`:$Tag"
