param(
    [string]$GatewayUrl     = "http://localhost:8080",
    [string]$WsUrl          = "ws://localhost:8083",
    [string]$KeycloakUrl    = "http://localhost:8090",
    [string]$Realm          = "ai-gallery",
    [string]$ClientId       = "frontend",
    [string]$UserPrefix     = "TestUser",
    [string]$Password       = "",
    [int]$Users             = 5,
    [int]$PromptsPerUser    = 2,
    [int]$IntervalSec       = 10,
    [int]$MaxWaitSec        = 90,
    [int]$PollIntervalSec   = 2,
    [int]$WsTimeoutSec      = 20,
    [string]$LogFile        = ""
)

$ErrorActionPreference = "Stop"
$script:LogFile = $LogFile

if ($script:LogFile) {
    $dir = Split-Path -Parent $script:LogFile
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    if (Test-Path $script:LogFile) { Remove-Item $script:LogFile -Force }
    New-Item -ItemType File -Path $script:LogFile -Force | Out-Null
}

function Write-Log {
    param([string]$Message)
    Write-Host $Message
    if ($script:LogFile) { Add-Content -Path $script:LogFile -Value $Message }
}

function Write-LogColor {
    param([string]$Message, [string]$Color)
    Write-Host $Message -ForegroundColor $Color
    if ($script:LogFile) { Add-Content -Path $script:LogFile -Value $Message }
}

function Get-PasswordForUser {
    param([string]$Username)
    if ([string]::IsNullOrEmpty($Password)) { return $Username }
    return $Password
}

function Get-Token {
    param([string]$Username)
    $pwd = Get-PasswordForUser -Username $Username
    $body = @{
        grant_type = "password"
        client_id  = $ClientId
        username   = $Username
        password   = $pwd
    }
    $res = Invoke-RestMethod -Method Post `
        -Uri "$KeycloakUrl/realms/$Realm/protocol/openid-connect/token" `
        -Body $body `
        -ContentType "application/x-www-form-urlencoded"
    return $res.access_token
}

function Get-JwtSub {
    param([string]$Token)
    $parts = $Token.Split(".")
    if ($parts.Length -lt 2) { return "" }
    $payload = $parts[1].Replace("-","+").Replace("_","/")
    switch ($payload.Length % 4) {
        2 { $payload += "==" }
        3 { $payload += "=" }
    }
    try {
        $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload))
        return ($json | ConvertFrom-Json).sub
    } catch {
        return ""
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers,
        [string]$Body = $null,
        [int]$TimeoutSec = 30
    )
    $req = [System.Net.HttpWebRequest]::Create($Url)
    $req.Method = $Method
    $req.Timeout = $TimeoutSec * 1000
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

$adjectives = @('realistic','abstract','minimal','cinematic','dramatic','bright','dark','cyberpunk')
$subjects   = @('cat','dog','city','car','flower','mountain','ocean','landscape')
$places     = @('mountain','ocean','city','forest','desert','beach','street','rooftop')
$times      = @('sunset','night','day','dusk')

function New-RandomPrompt {
    $adj = $adjectives | Get-Random
    $subj = $subjects | Get-Random
    $style = $adjectives | Get-Random
    $place = $places | Get-Random
    $time = $times | Get-Random
    $art = "a"
    if ($adj -match '^[aeiou]') { $art = "an" }
    return "$art $adj $subj on a $place at $time, $style"
}

function Extract-WsStatus {
    param($Obj)
    if (-not $Obj) { return "" }
    if ($Obj.status -and ($Obj.status -is [string])) { return $Obj.status }
    if ($Obj.type -and ($Obj.type -is [string])) {
        if ($Obj.type -match "COMPLETED") { return "COMPLETED" }
        if ($Obj.type -match "FAILED")    { return "FAILED" }
        if ($Obj.type -match "CANCELLED") { return "CANCELLED" }
    }
    if ($Obj.event -and ($Obj.event -is [string])) {
        if ($Obj.event -match "COMPLETED") { return "COMPLETED" }
        if ($Obj.event -match "FAILED")    { return "FAILED" }
        if ($Obj.event -match "CANCELLED") { return "CANCELLED" }
    }
    return ""
}

function Extract-WsImageUrl {
    param($Obj)
    if (-not $Obj) { return "" }
    if ($Obj.imageUrl) { return $Obj.imageUrl }
    if ($Obj.data -and $Obj.data.imageUrl) { return $Obj.data.imageUrl }
    if ($Obj.payload -and $Obj.payload.imageUrl) { return $Obj.payload.imageUrl }
    return ""
}

function Wait-ViaWs {
    param([string]$JobId, [string]$Token, [int]$TimeoutSec)

    $uri = "$WsUrl/ws/generations/$JobId`?token=$([uri]::EscapeDataString($Token))"
    $result = [pscustomobject]@{
        Status   = "TIMEOUT"
        ImageUrl = ""
        Error    = ""
        Events   = @()
    }

    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $cts = New-Object System.Threading.CancellationTokenSource
    $cts.CancelAfter([TimeSpan]::FromSeconds($TimeoutSec + 5))

    try {
        $ws.ConnectAsync([uri]$uri, $cts.Token).GetAwaiter().GetResult()
    } catch {
        $result.Status = "WS_FAIL"
        $result.Error  = $_.Exception.Message
        try { $ws.Dispose() } catch {}
        return $result
    }

    $buffer  = New-Object byte[] 16384
    $segment = New-Object System.ArraySegment[byte] -ArgumentList @(,$buffer)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)

    while ((Get-Date) -lt $deadline) {
        $task = $ws.ReceiveAsync($segment, $cts.Token)
        try { $task.Wait(1000) | Out-Null } catch {}

        if ($task.IsCompleted) {
            try {
                if ($task.Result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                    break
                }
                $msg = [Text.Encoding]::UTF8.GetString($buffer, 0, $task.Result.Count)
                $result.Events += $msg
                try {
                    $obj = $msg | ConvertFrom-Json
                } catch {
                    continue
                }
                $status = Extract-WsStatus -Obj $obj
                if ($status -eq "COMPLETED") {
                    $result.Status = "COMPLETED"
                    $result.ImageUrl = Extract-WsImageUrl -Obj $obj
                    break
                }
                if ($status -eq "FAILED") {
                    $result.Status = "FAILED"
                    if ($obj.errorMessage) { $result.Error = $obj.errorMessage }
                    break
                }
                if ($status -eq "CANCELLED") {
                    $result.Status = "CANCELLED"
                    break
                }
            } catch {}
        } elseif ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
            break
        }
    }

    try { $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", [Threading.CancellationToken]::None).Wait(1000) | Out-Null } catch {}
    try { $ws.Dispose() } catch {}
    return $result
}

function Wait-ViaPolling {
    param([string]$JobId, [hashtable]$Headers, [int]$MaxWaitSec, [int]$PollIntervalSec)
    $deadline = (Get-Date).AddSeconds($MaxWaitSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $PollIntervalSec
        $r = Invoke-Api -Method "GET" -Url "$GatewayUrl/api/v1/generations/$JobId" -Headers $Headers
        if ($r.Status -eq 200) {
            try {
                $obj = $r.Body | ConvertFrom-Json
                if ($obj.status -eq "COMPLETED") {
                    return [pscustomobject]@{ Status = "COMPLETED"; ImageUrl = $obj.imageUrl; Error = "" }
                }
                if ($obj.status -eq "FAILED") {
                    return [pscustomobject]@{ Status = "FAILED"; ImageUrl = ""; Error = $obj.errorMessage }
                }
                if ($obj.status -eq "CANCELLED") {
                    return [pscustomobject]@{ Status = "CANCELLED"; ImageUrl = ""; Error = "" }
                }
            } catch {}
        } elseif ($r.Status -eq 429) {
            Start-Sleep -Seconds 2
        }
    }
    return [pscustomobject]@{ Status = "TIMEOUT"; ImageUrl = ""; Error = "" }
}

function New-OneJob {
    param(
        [string]$User,
        [string]$Token,
        [string]$UserId,
        [int]$Index,
        [int]$Total,
        [int]$WsTimeoutSec,
        [int]$MaxWaitSec,
        [int]$PollIntervalSec
    )

    $prompt = New-RandomPrompt
    $idem   = [guid]::NewGuid().ToString()
    $body = @{
        prompt   = $prompt
        model    = "flux"
        size     = "512x512"
        count    = 1
        isPublic = $true
    } | ConvertTo-Json -Compress

    Write-Log "--- [$User] job $Index/$Total ---"
    Write-Log "  prompt: $prompt"

    $h = @{
        "Content-Type"    = "application/json"
        "Authorization"   = "Bearer $Token"
        "Idempotency-Key" = $idem
    }

    $createStart = Get-Date
    $r = Invoke-Api -Method "POST" -Url "$GatewayUrl/api/v1/generations" -Headers $h -Body $body

    if ($r.Status -ne 202) {
        Write-LogColor "  POST -> $($r.Status) $($r.Error) body=$($r.Body)" "Red"
        return [pscustomobject]@{
            User = $User; Prompt = $prompt; JobId = ""
            Status = "POST_FAIL"; ImageUrl = ""; Error = $r.Body; DurationSec = 0
        }
    }

    $parsed = $r.Body | ConvertFrom-Json
    $jobId  = $parsed.jobId
    $postMs = [int]((Get-Date) - $createStart).TotalMilliseconds
    Write-Log "  jobId: $jobId ($postMs ms)"

    $ws = Wait-ViaWs -JobId $jobId -Token $Token -TimeoutSec $WsTimeoutSec
    if ($ws.Status -eq "COMPLETED" -or $ws.Status -eq "FAILED" -or $ws.Status -eq "CANCELLED") {
        $dur = [math]::Round(((Get-Date) - $createStart).TotalSeconds, 1)
        Write-Log "  WS: $($ws.Status) (${dur}s)"
        return [pscustomobject]@{
            User = $User; Prompt = $prompt; JobId = $jobId
            Status = $ws.Status; ImageUrl = $ws.ImageUrl; Error = $ws.Error; DurationSec = $dur
        }
    }

    Write-Log "  WS: $($ws.Status) -> polling"
    $poll = Wait-ViaPolling -JobId $jobId -Headers $h -MaxWaitSec $MaxWaitSec -PollIntervalSec $PollIntervalSec
    $dur = [math]::Round(((Get-Date) - $createStart).TotalSeconds, 1)
    Write-Log "  POLL: $($poll.Status) (${dur}s)"

    if ($poll.Status -eq "COMPLETED" -and -not $poll.ImageUrl) {
        $g = Invoke-Api -Method "GET" -Url "$GatewayUrl/api/v1/generations/$jobId" -Headers $h
        if ($g.Status -eq 200) {
            $poll.ImageUrl = ($g.Body | ConvertFrom-Json).imageUrl
            Write-Log "  GET imageUrl=$($poll.ImageUrl)"
        }
    }

    return [pscustomobject]@{
        User = $User; Prompt = $prompt; JobId = $jobId
        Status = $poll.Status; ImageUrl = $poll.ImageUrl; Error = $poll.Error; DurationSec = $dur
    }
}

Write-LogColor "=== full-e2e ===" "Cyan"
Write-Log "Gateway:    $GatewayUrl"
Write-Log "WS:         $WsUrl"
Write-Log "Keycloak:   $KeycloakUrl"
Write-Log "Users:      $Users x $PromptsPerUser prompts (total $($Users * $PromptsPerUser))"
Write-Log "Interval:   ${IntervalSec}s after each terminal"
Write-Log "WS timeout: ${WsTimeoutSec}s, Poll max: ${MaxWaitSec}s"
Write-Log ""

$allResults = @()
$userTokens = @{}
$userIds    = @{}
$userNames  = @()

for ($u = 1; $u -le $Users; $u++) {
    $username = "$UserPrefix$u"
    $userNames += $username
    Write-LogColor "=== Login ($username) ===" "Cyan"
    try {
        $token = Get-Token -Username $username
        $sub = Get-JwtSub $token
        $userTokens[$username] = $token
        $userIds[$username] = $sub
        Write-Log "  Token OK, sub=$sub"
    } catch {
        Write-LogColor "  Login FAILED: $($_.Exception.Message)" "Red"
        if ($_.ErrorDetails.Message) { Write-LogColor "  $($_.ErrorDetails.Message)" "Red" }
        Write-Log ""
        continue
    }
    Write-Log ""
}

foreach ($username in $userNames) {
    if (-not $userTokens.ContainsKey($username)) { continue }

    $token = $userTokens[$username]
    $sub   = $userIds[$username]

    for ($p = 1; $p -le $PromptsPerUser; $p++) {
        $res = New-OneJob `
            -User $username `
            -Token $token `
            -UserId $sub `
            -Index $p `
            -Total $PromptsPerUser `
            -WsTimeoutSec $WsTimeoutSec `
            -MaxWaitSec $MaxWaitSec `
            -PollIntervalSec $PollIntervalSec

        $allResults += $res

        if ($res.Status -eq "COMPLETED") {
            Write-LogColor "  => COMPLETED url=$($res.ImageUrl)" "Green"
        } elseif ($res.Status -eq "FAILED") {
            Write-LogColor "  => FAILED $($res.Error)" "Red"
        } else {
            Write-LogColor "  => $($res.Status)" "Yellow"
        }
        Write-Log ""

        if ($p -lt $PromptsPerUser) {
            Write-Log "  Cool-down ${IntervalSec}s..."
            Start-Sleep -Seconds $IntervalSec
        }
    }
}

Write-Log ""
Write-LogColor "=== Job summary ===" "Cyan"
foreach ($r in $allResults) {
    Write-Log ("  {0,-12} {1,-10} {2,6}s  {3}" -f $r.User, $r.Status, $r.DurationSec, $r.JobId)
}

$completed = @($allResults | Where-Object { $_.Status -eq "COMPLETED" }).Count
$failed    = @($allResults | Where-Object { $_.Status -eq "FAILED" }).Count
$timeout   = @($allResults | Where-Object { $_.Status -eq "TIMEOUT" }).Count
$postFail  = @($allResults | Where-Object { $_.Status -eq "POST_FAIL" }).Count

Write-Log ""
Write-Log "Total:     $($allResults.Count)"
Write-LogColor "COMPLETED: $completed" "Green"
Write-LogColor "FAILED:    $failed" "Red"
Write-LogColor "TIMEOUT:   $timeout" "Magenta"
Write-LogColor "POST_FAIL: $postFail" "Yellow"

$durations = @($allResults | Where-Object { $_.DurationSec -gt 0 } | ForEach-Object { $_.DurationSec })
if ($durations.Count -gt 0) {
    $avg = [math]::Round((($durations | Measure-Object -Average).Average), 1)
    Write-Log "Avg time:  $avg s"
}

Write-Log ""
Write-LogColor "=== Chain checks ===" "Cyan"

$firstUser  = $userNames | Where-Object { $userTokens.ContainsKey($_) } | Select-Object -First 1
$firstToken = $userTokens[$firstUser]
$firstUserId = $userIds[$firstUser]
$chainHeaders = @{ "Authorization" = "Bearer $firstToken" }

Write-Log "Probe as: $firstUser (sub=$firstUserId)"
Write-Log ""

$feedResp = Invoke-Api -Method "GET" -Url "$GatewayUrl/api/v1/feed?limit=10" -Headers $chainHeaders
if ($feedResp.Status -eq 200) {
    try {
        $feedBody = $feedResp.Body | ConvertFrom-Json
        $items = $feedBody.items
        $count = 0
        if ($items) { $count = @($items).Count }
        if ($count -ge 5) {
            Write-LogColor "  FEED:           $count items" "Green"
        } else {
            Write-LogColor "  FEED:           $count items (expected >= 5)" "Yellow"
        }
    } catch {
        Write-LogColor "  FEED:           200, parse failed" "Yellow"
    }
} else {
    Write-LogColor "  FEED:           $($feedResp.Status) unavailable" "Yellow"
}

$tagsResp = Invoke-Api -Method "GET" -Url "$GatewayUrl/api/v1/tags" -Headers $chainHeaders
if ($tagsResp.Status -eq 200) {
    try {
        $tags = $tagsResp.Body | ConvertFrom-Json
        $tagCount = 0
        if ($tags -is [array]) { $tagCount = $tags.Count }
        elseif ($tags.items) { $tagCount = @($tags.items).Count }
        if ($tagCount -gt 0) {
            Write-LogColor "  TAGS:           $tagCount" "Green"
        } else {
            Write-LogColor "  TAGS:           0 (tagging unavailable or empty)" "Yellow"
        }
    } catch {
        Write-LogColor "  TAGS:           200, parse failed" "Yellow"
    }
} else {
    Write-LogColor "  TAGS:           $($tagsResp.Status) unavailable" "Yellow"
}

$recHeaders = @{ "Authorization" = "Bearer $firstToken"; "X-User-Id" = $firstUserId }
$recResp = Invoke-Api -Method "GET" -Url "$GatewayUrl/api/v1/recommendations?limit=10" -Headers $recHeaders
if ($recResp.Status -eq 200) {
    $recLen = 0
    if ($recResp.Body) { $recLen = $recResp.Body.Length }
    Write-LogColor "  RECOMMENDATIONS: 200 (len $recLen)" "Green"
} else {
    Write-LogColor "  RECOMMENDATIONS: $($recResp.Status) unavailable" "Yellow"
}

$billingHeaders = @{ "Authorization" = "Bearer $firstToken"; "X-User-Id" = $firstUserId }
$billingResp = Invoke-Api -Method "GET" -Url "$GatewayUrl/api/v1/billing/me" -Headers $billingHeaders
if ($billingResp.Status -eq 200) {
    Write-LogColor "  BILLING:        200" "Green"
    Write-Log "    $($billingResp.Body)"
} else {
    $billingResp2 = Invoke-Api -Method "GET" -Url "$GatewayUrl/api/v1/billing/me?userId=$firstUserId" -Headers $billingHeaders
    if ($billingResp2.Status -eq 200) {
        Write-LogColor "  BILLING:        200 (via query)" "Green"
        Write-Log "    $($billingResp2.Body)"
    } else {
        Write-LogColor "  BILLING:        $($billingResp.Status) unavailable" "Yellow"
    }
}

Write-Log ""
Write-LogColor "=== DoD check ===" "Cyan"

$total = $allResults.Count
$dodPass = $true

if ($completed -eq $total) {
    Write-LogColor "  [OK]  $completed/$total COMPLETED" "Green"
} else {
    Write-LogColor "  [FAIL] $completed/$total COMPLETED" "Red"
    $dodPass = $false
}

$imageUrlsOk = @($allResults | Where-Object { $_.Status -eq "COMPLETED" -and $_.ImageUrl }).Count
if ($imageUrlsOk -eq $completed -and $completed -gt 0) {
    Write-LogColor "  [OK]  $imageUrlsOk/$completed jobs have imageUrl" "Green"
} else {
    Write-LogColor "  [FAIL] $imageUrlsOk/$completed jobs have imageUrl" "Red"
    $dodPass = $false
}

if ($feedResp.Status -eq 200 -and $count -ge 5) {
    Write-LogColor "  [OK]  Feed has $count items" "Green"
} else {
    Write-LogColor "  [FAIL] Feed check failed" "Red"
    $dodPass = $false
}

if ($tagsResp.Status -eq 200 -and $tagCount -gt 0) {
    Write-LogColor "  [OK]  Tags non-empty ($tagCount)" "Green"
} else {
    Write-LogColor "  [WARN] Tags empty or unavailable" "Yellow"
}

if ($billingResp.Status -eq 200 -or $billingResp2.Status -eq 200) {
    Write-LogColor "  [OK]  Billing reachable" "Green"
} else {
    Write-LogColor "  [WARN] Billing unavailable" "Yellow"
}

Write-Log ""
if ($dodPass) {
    Write-LogColor "=== RESULT: PASS ===" "Green"
} else {
    Write-LogColor "=== RESULT: FAIL ===" "Red"
}

if ($script:LogFile) {
    Write-Log ""
    Write-Log "Log: $($script:LogFile)"
}