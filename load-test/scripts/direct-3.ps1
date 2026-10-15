param(
    [string]$GenerationUrl = "http://localhost:8082",
    [string]$KeycloakUrl   = "http://localhost:8090",
    [string]$Realm         = "ai-gallery",
    [string]$ClientId      = "frontend",
    [string]$Username      = "TestUser1",
    [string]$Password      = "TestUser1",
    [string]$Path          = "/api/v1/generations",
    [switch]$NoAuth,
    [switch]$ShowBody,
    [int]$Prompts          = 3,
    [int]$IntervalSec      = 60,
    [int]$PollIntervalSec  = 2,
    [int]$MaxWaitSec       = 300,
    [string]$LogFile       = ""
)

$ErrorActionPreference = "Stop"

function Write-Line {
    param([string]$Message)
    if ($LogFile) { Add-Content -Path $LogFile -Value $Message }
}

if ($LogFile) {
    if (Test-Path $LogFile) { Remove-Item $LogFile -Force }
    New-Item -ItemType File -Path $LogFile -Force | Out-Null
}

function Get-Token {
    $body = @{
        grant_type = "password"
        client_id  = $ClientId
        username   = $Username
        password   = $Password
    }
    $res = Invoke-RestMethod -Method Post `
        -Uri "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" `
        -Body $body `
        -ContentType "application/x-www-form-urlencoded"
    return $res.access_token
}

function Get-JwtSub {
    param([string]$Token)
    if (-not $Token) { return "" }
    $parts = $Token.Split(".")
    if ($parts.Length -lt 2) { return "" }
    $payload = $parts[1].Replace("-","+").Replace("_","/")
    switch ($payload.Length % 4) {
        2 { $payload += "==" }
        3 { $payload += "=" }
    }
    try {
        $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload))
        $obj = $json | ConvertFrom-Json
        return $obj.sub
    } catch {
        return ""
    }
}

function Headers {
    $h = @{ "Content-Type" = "application/json" }
    if (-not $NoAuth -and $script:token) {
        $h["Authorization"] = "Bearer $script:token"
        if ($script:userId) {
            $h["X-User-Id"] = $script:userId
        }
    }
    return $h
}

function Invoke-Request {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers,
        [string]$Body = $null
    )

    $req = [System.Net.HttpWebRequest]::Create($Url)
    $req.Method = $Method
    foreach ($k in $Headers.Keys) {
        if ($k -eq "Content-Type") { $req.ContentType = $Headers[$k] }
        else { $req.Headers[$k] = $Headers[$k] }
    }

    if ($Body) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
        $req.ContentLength = $bytes.Length
        $stream = $req.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Close()
    }

    try {
        $resp = $req.GetResponse()
        $code = [int]$resp.StatusCode
        $reader = New-Object IO.StreamReader($resp.GetResponseStream())
        $respBody = $reader.ReadToEnd()
        $reader.Close()
        $resp.Close()
        return [pscustomobject]@{ Status = $code; Body = $respBody; Error = "" }
    } catch [System.Net.WebException] {
        $r = $_.Exception.Response
        if ($r) {
            $code = [int]$r.StatusCode
            $reader = New-Object IO.StreamReader($r.GetResponseStream())
            $respBody = $reader.ReadToEnd()
            $reader.Close()
            return [pscustomobject]@{ Status = $code; Body = $respBody; Error = $_.Exception.Message }
        }
        return [pscustomobject]@{ Status = 0; Body = ""; Error = $_.Exception.Message }
    }
}

$subjects   = @('cat','dog','city','car','flower','mountain','ocean','landscape')
$adjectives = @('realistic','abstract','minimal','cinematic','dramatic','bright','dark','cyberpunk')
$places     = @('mountain','ocean','city','forest','desert','beach','street','rooftop')
$times      = @('sunset','night','day','dusk')

function New-RandomPrompt {
    $adj   = $adjectives | Get-Random
    $subj  = $subjects   | Get-Random
    $style = $adjectives | Get-Random
    $place = $places     | Get-Random
    $time  = $times      | Get-Random
    $article = "a"
    if ($adj -match '^[aeiou]') { $article = "an" }
    return "$article $adj $subj on a $place at $time, $style"
}

Write-Host "=== direct-3 ===" -ForegroundColor Cyan
Write-Host "Target:    $GenerationUrl$Path"
Write-Host "User:      $Username"
Write-Host "Prompts:   $Prompts (interval ${IntervalSec}s)"
Write-Host "Auth:      $(if ($NoAuth) { 'no' } else { 'yes' })"
Write-Host ""

$script:token  = ""
$script:userId = ""

if (-not $NoAuth) {
    Write-Host "=== Login ($Username) ===" -ForegroundColor Cyan
    try {
        $script:token = Get-Token
        Write-Host "Token OK (len=$($script:token.Length))"
        $script:userId = Get-JwtSub $script:token
        Write-Host "JWT sub: $script:userId"
    } catch {
        Write-Host "Login FAILED: $($_.Exception.Message)" -ForegroundColor Red
        if ($_.ErrorDetails.Message) {
            Write-Host $_.ErrorDetails.Message
        }
        exit 1
    }
    Write-Host ""
}

function Send-OnePrompt {
    param([int]$Index)

    $prompt  = New-RandomPrompt
    $idemKey = [guid]::NewGuid().ToString()
    $body = @{
        prompt   = $prompt
        model    = "flux"
        size     = "512x512"
        count    = 1
        isPublic = $true
    } | ConvertTo-Json -Compress

    Write-Host "--- Prompt $Index/$Prompts ---" -ForegroundColor Yellow
    Write-Host "  prompt: $prompt"
    Write-Line "[$((Get-Date).ToString('HH:mm:ss'))] PROMPT $Index `"$prompt`""

    $url = "$GenerationUrl$Path"
    $h = Headers
    $h["Idempotency-Key"] = $idemKey

    $createStart = Get-Date
    $resp = Invoke-Request -Method "POST" -Url $url -Headers $h -Body $body

    if ($resp.Status -ne 202) {
        Write-Host "  POST -> $($resp.Status) $($resp.Error)" -ForegroundColor Red
        if ($resp.Body) {
            Write-Host "  response body: $($resp.Body)" -ForegroundColor DarkRed
        }
        Write-Line "[$((Get-Date).ToString('HH:mm:ss'))] POST_FAIL status=$($resp.Status) body=$($resp.Body)"
        return [pscustomobject]@{ Index = $Index; Status = "POST_FAIL"; JobId = ""; DurationSec = 0; ImageUrl = ""; Error = $resp.Body }
    }

    if ($ShowBody) {
        Write-Host "  raw 202 body: $($resp.Body)" -ForegroundColor DarkGray
    }

    $parsed = $resp.Body | ConvertFrom-Json
    $jobId = $parsed.jobId
    Write-Host "  jobId: $jobId"
    Write-Host "  202 in $([int]((Get-Date) - $createStart).TotalMilliseconds) ms"
    Write-Line "[$((Get-Date).ToString('HH:mm:ss'))] POST 202 jobId=$jobId"

    $pollStart  = Get-Date
    $lastStatus = "QUEUED"
    $final      = "TIMEOUT"
    $imageUrl   = ""
    $errMsg     = ""

    while ($true) {
        $elapsed = ((Get-Date) - $pollStart).TotalSeconds
        if ($elapsed -ge $MaxWaitSec) {
            Write-Host "  TIMEOUT (last: $lastStatus)" -ForegroundColor Red
            Write-Line "[$((Get-Date).ToString('HH:mm:ss'))] TIMEOUT jobId=$jobId last=$lastStatus"
            break
        }

        Start-Sleep -Seconds $PollIntervalSec

        $statusUrl = "$GenerationUrl$Path/$jobId"
        $s = Invoke-Request -Method "GET" -Url $statusUrl -Headers (Headers)

        if ($s.Status -ne 200) {
            continue
        }

        try {
            $obj = $s.Body | ConvertFrom-Json
        } catch {
            continue
        }

        if ($obj.status -ne $lastStatus) {
            $lastStatus = $obj.status
            Write-Host "  status: $lastStatus" -ForegroundColor DarkGray
            Write-Line "[$((Get-Date).ToString('HH:mm:ss'))] STATUS $lastStatus"
        }

        if ($obj.status -eq "COMPLETED") {
            $final = "COMPLETED"
            $imageUrl = $obj.imageUrl
            break
        }
        if ($obj.status -eq "FAILED") {
            $final = "FAILED"
            $errMsg = $obj.errorMessage
            break
        }
        if ($obj.status -eq "CANCELLED") {
            $final = "CANCELLED"
            break
        }
    }

    $dur = [math]::Round(((Get-Date) - $createStart).TotalSeconds, 1)
    $color = switch ($final) {
        "COMPLETED" { "Green" }
        "FAILED"    { "Red" }
        "TIMEOUT"   { "Magenta" }
        default     { "Yellow" }
    }
    Write-Host "  => $final (${dur}s)" -ForegroundColor $color
    if ($imageUrl) { Write-Host "  imageUrl: $imageUrl" -ForegroundColor Green }
    if ($errMsg)   { Write-Host "  error:    $errMsg" -ForegroundColor Red }
    Write-Host ""

    Write-Line "[$((Get-Date).ToString('HH:mm:ss'))] RESULT $final ${dur}s url=$imageUrl err=$errMsg"

    return [pscustomobject]@{
        Index       = $Index
        Status      = $final
        JobId       = $jobId
        DurationSec = $dur
        ImageUrl    = $imageUrl
        Error       = $errMsg
    }
}

$results = @()
for ($i = 1; $i -le $Prompts; $i++) {
    $res = Send-OnePrompt -Index $i
    $results += $res
    if ($i -lt $Prompts) {
        Write-Host "Waiting ${IntervalSec}s before next prompt..." -ForegroundColor DarkGray
        Write-Host ""
        Start-Sleep -Seconds $IntervalSec
    }
}

Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
foreach ($r in $results) {
    Write-Host ("  #{0}  {1,-10}  {2,6}s  {3}" -f $r.Index, $r.Status, $r.DurationSec, $r.ImageUrl)
}

$completed = @($results | Where-Object { $_.Status -eq "COMPLETED" }).Count
$failed    = @($results | Where-Object { $_.Status -eq "FAILED" }).Count
$timeout   = @($results | Where-Object { $_.Status -eq "TIMEOUT" }).Count
$postFail  = @($results | Where-Object { $_.Status -eq "POST_FAIL" }).Count

Write-Host ""
Write-Host "Total:     $($results.Count)"
Write-Host "COMPLETED: $completed" -ForegroundColor Green
Write-Host "FAILED:    $failed" -ForegroundColor Red
Write-Host "TIMEOUT:   $timeout" -ForegroundColor Magenta
Write-Host "POST_FAIL: $postFail" -ForegroundColor Yellow

$durations = @($results | Where-Object { $_.Status -eq "COMPLETED" } | ForEach-Object { $_.DurationSec })
if ($durations.Count -gt 0) {
    $avg = [math]::Round((($durations | Measure-Object -Average).Average), 1)
    Write-Host "Avg time:  $avg s"
}

if ($LogFile) {
    Write-Host ""
    Write-Host "Log: $LogFile" -ForegroundColor Green
}