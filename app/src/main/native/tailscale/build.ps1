param(
    [string]$GoExecutable = 'go.exe',
    [Parameter(Mandatory)][string]$AndroidNdk,
    [Parameter(Mandatory)][string]$BuildRoot,
    [Parameter(Mandatory)][string]$OutputRoot,
    [string]$ModuleCache,
    [switch]$HostTest
)
$ErrorActionPreference='Stop'
$go=(Get-Command $GoExecutable -ErrorAction Stop).Source
if ((& $go version) -notmatch 'go1\.26\.10(?:\s|$)') { throw 'Use the reviewed Go 1.26.10 toolchain.' }
if ((Get-Content -LiteralPath (Join-Path $AndroidNdk 'source.properties') -Raw) -notmatch 'Pkg\.Revision\s*=\s*30\.0\.15729638-beta2') { throw 'Use NDK 30.0.15729638-beta2.' }
New-Item -ItemType Directory -Force -Path $BuildRoot,$OutputRoot | Out-Null
$env:GOCACHE=Join-Path $BuildRoot 'gocache'
$env:GOMODCACHE=if($ModuleCache){$ModuleCache}else{Join-Path $BuildRoot 'modules'}
$env:GOTOOLCHAIN='local'
$env:GOMAXPROCS='4'
$env:GOFLAGS=''
$env:CGO_ENABLED='0'
Push-Location $PSScriptRoot
try {
    & $go mod download tailscale.com
    if($LASTEXITCODE){throw 'Tailscale module download failed.'}
    $module=Join-Path $env:GOMODCACHE 'tailscale.com@v1.96.4'
    $original=Join-Path $module 'tsnet/tsnet.go'
    if((Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash -ne '56CE84F1361F7A0BE6330840B399944D72531F40181A61BC3D646F787E7F7002') { throw 'Upstream tsnet differs from reviewed v1.96.4.' }
    $patched=Join-Path $BuildRoot 'tailscale-source'
    $stamp=Join-Path $patched '.collector-patch-v1'
    if(!(Test-Path -LiteralPath $stamp)) {
        New-Item -ItemType Directory -Force -Path $patched | Out-Null
        Get-ChildItem -LiteralPath $module -Force | Copy-Item -Destination $patched -Recurse -Force
        Get-ChildItem -LiteralPath $patched -File -Recurse | ForEach-Object {$_.IsReadOnly=$false}
        $source=[IO.File]::ReadAllText($original)
        $start=$source.IndexOf('func (s *Server) startLogger(')
        $end=$source.IndexOf('type closeOnErrorPool',$start)
        if($start -lt 0 -or $end -le $start){throw 'Private logger patch anchor missing.'}
        $replacement="func (s *Server) startLogger(closePool *closeOnErrorPool, health *health.Tracker, tsLogf logger.Logf) error {`n`treturn nil`n}`n`n"
        $source=$source.Substring(0,$start)+$replacement+$source.Substring($end)
        $source=[regex]::Replace($source,'(?m)^\s*"tailscale.com/(?:logpolicy|util/clientmetric|util/testenv)"\r?\n','')
        $anchor='// LocalClient returns a LocalClient that speaks to s.'
        if(!$source.Contains($anchor)){throw 'Network monitor patch anchor missing.'}
        $source=$source.Replace($anchor,"// NetworkChanged forwards Android connectivity changes without changing routing.`nfunc (s *Server) NetworkChanged() {`n`tif s.netMon != nil { s.netMon.InjectEvent() }`n}`n`n"+$anchor)
        [IO.File]::WriteAllText((Join-Path $patched 'tsnet/tsnet.go'),$source,[Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($stamp,'private logging + explicit network notification v1')
    }
    $mod=Join-Path $BuildRoot 'build.mod'
    Copy-Item -LiteralPath 'go.mod' -Destination $mod -Force
    if(Test-Path 'go.sum'){Copy-Item -LiteralPath 'go.sum' -Destination (Join-Path $BuildRoot 'build.sum') -Force}
    & $go mod edit "-modfile=$mod" "-replace=tailscale.com=$($patched.Replace('\','/'))"
    if($LASTEXITCODE){throw 'Native module preparation failed.'}
    & $go mod tidy "-modfile=$mod"
    if($LASTEXITCODE){throw 'Native dependency resolution failed.'}
    if($HostTest){
        & $go test -p 2 "-modfile=$mod" -tags=ts_omit_logtail ./...
        if($LASTEXITCODE){throw 'Native persistence checks failed.'}
        return
    }
    $notices=Join-Path $BuildRoot 'notices'
    New-Item -ItemType Directory -Force -Path $notices | Out-Null
    $licenseText=[Text.StringBuilder]::new()
    [void]$licenseText.AppendLine('Collector embedded Tailscale dependency notices')
    [void]$licenseText.AppendLine('Tailscale patches: disable automatic log upload; forward Android network changes.')
    $goRoot=(& $go env GOROOT).Trim()
    [void]$licenseText.AppendLine([IO.File]::ReadAllText((Join-Path $goRoot 'LICENSE')))
    $modules=& $go list "-modfile=$mod" -m -f '{{.Path}}|{{.Version}}|{{.Dir}}' all
    if($LASTEXITCODE){throw 'Cannot enumerate native dependency licenses.'}
    foreach($line in $modules){
        $parts=$line -split '\|',3
        if($parts.Length -ne 3 -or !$parts[2] -or $parts[0] -eq 'com.bydcollector/ha-tailscale'){continue}
        foreach($license in (Get-ChildItem -LiteralPath $parts[2] -File | Where-Object Name -Match '^(LICENSE|COPYING|NOTICE)(\.|$)')) {
            [void]$licenseText.AppendLine("`n$($parts[0]) $($parts[1]) - $($license.Name)`n")
            [void]$licenseText.AppendLine([IO.File]::ReadAllText($license.FullName))
        }
    }
    [IO.File]::WriteAllText((Join-Path $notices 'tailscale-dependency-notices.txt'),$licenseText.ToString(),[Text.UTF8Encoding]::new($false))
    $env:CGO_ENABLED='1'
    $env:GOOS='android'
    $env:GOARCH='arm64'
    $env:CC=Join-Path $AndroidNdk 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
    $env:GOARM=''
    $out=Join-Path $OutputRoot 'arm64-v8a'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    & $go build -p 2 "-modfile=$mod" -tags=ts_omit_logtail -trimpath -buildmode=c-shared '-ldflags=-s -w -buildid=' -o (Join-Path $out 'libcollector_tailscale.so') .
    if($LASTEXITCODE){throw 'Native build failed for arm64-v8a'}
} finally { Pop-Location }
