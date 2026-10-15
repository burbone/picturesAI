param(
    [string]$KeycloakUrl = "http://localhost:8090",
    [string]$GatewayUrl  = "http://localhost:8080",
    [string]$Realm       = "ai-gallery",
    [string]$ClientId    = "frontend",
    [string]$Username    = "user",
    [string]$Password    = "picturesAI",
    [string]$PgContainer = "picturesAI-postgres",
    [string]$PgHost      = "127.0.0.1",
    [int]$PgPort         = 5433,
    [string]$PgUser      = "picturesai",
    [string]$PgDatabase  = "generations_db",
    [string]$PgMode      = "docker",
    [int]$JobCount       = 5,
    [int]$PollIntervalSec = 5,
    [int]$MaxWaitSec     = 60,
    [string]$LogFile     = ""
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message, [string]$Color = "White")
    $line = $Message
    if ($LogFile) {
        Add-Content -Path $LogFile -Value $line
    }
    Write-Host $line -ForegroundColor $Color
}

if ($LogFile) {
    if (Test-Path $LogFile) { Remove-Item $LogFile -Force }
    New-Item -ItemType File -Path $LogFile -Force | Out-Null
}

Write-Log "=== trace-one-user ===" Cyan
Write-Log "Gateway:  $GatewayUrl"
Write-Log "Keycloak: $KeycloakUrl"
Write-Log "Realm:    $Realm / user: $Username"
Write-Log "Jobs:     $JobCount"
Write-Log ""

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

$subjects   = @('cat','dog','portrait','city','car','flower','mountain','ocean','landscape')
$adjectives = @('realistic','abstract','minimal','cinematic','dramatic','bright','dark','cyberpunk')
$places     = @('mountain','ocean','city','forest','desert','beach','street','rooftop')
$times      = @('sunset','night','day','dawn','dusk')

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

Write-Log "=== Login ===" Cyan
$token = Get-Token
Write-Log "Token OK (len=$($token.Length))"

Write-Log ""
Write-Log "=== Creating $JobCount jobs ===" Cyan
$jobs = @()
for ($i = 1; $i -le $JobCount; $i++) {
    $prompt  = New-RandomPrompt
    $idemKey = [guid]::NewGuid().ToString()
    $body = @{
        prompt   = $prompt
        model    = "flux"
        size     = "512x512"
        count    = 1
        isPublic = $true
    } | ConvertTo-Json -Compress

    try {
        $r = Invoke-RestMethod -Method Post `
            -Uri "$GatewayUrl/api/v1/generations" `
            -Headers @{
                Authorization     = "Bearer $token"
                "Idempotency-Key" = $idemKey
            } `
            -Body $body `
            -ContentType "application/json"

        $now = Get-Date
        $job = [pscustomobject]@{
            Index       = $i
            JobId       = $r.jobId
            Prompt      = $prompt
            CreatedAt   = $now
            StartedAt   = $null
            CompletedAt = $null
            FinalStatus = "QUEUED"
            LastStatus  = "QUEUED"
            Transitions = @([pscustomobject]@{ Time = $now; Status = "QUEUED" })
            Terminal    = $false
        }
        $jobs += $job
        Write-Log "[$($now.ToString('HH:mm:ss'))] jobId=$($r.jobId) status=QUEUED (created)"
    } catch {
        Write-Log "POST failed for job $i : $_" Red
    }
    Start-Sleep -Milliseconds 200
}

if ($jobs.Count -eq 0) {
    Write-Log "No jobs created, abort" Red
    exit 1
}

Write-Log ""
Write-Log "=== Polling statuses (interval=${PollIntervalSec}s, max=${MaxWaitSec}s) ===" Cyan
$pollStart = Get-Date
$lastReauth = Get-Date
$terminalStates = @("COMPLETED", "FAILED", "CANCELLED")

while ($true) {
    $pending = @($jobs | Where-Object { -not $_.Terminal })
    if ($pending.Count -eq 0) { break }

    $elapsed = ((Get-Date) - $pollStart).TotalSeconds
    if ($elapsed -ge $MaxWaitSec) {
        Write-Log "Max wait ${MaxWaitSec}s reached, stopping poll" Yellow
        break
    }

    if (((Get-Date) - $lastReauth).TotalMinutes -ge 4) {
        try {
            $token = Get-Token
            $lastReauth = Get-Date
        } catch {
            Write-Log "Reauth failed: $_" Yellow
        }
    }

    Start-Sleep -Seconds $PollIntervalSec
    $now = Get-Date

    foreach ($job in $jobs) {
        if ($job.Terminal) { continue }
        try {
            $r = Invoke-RestMethod -Method Get `
                -Uri "$GatewayUrl/api/v1/generations/$($job.JobId)" `
                -Headers @{ Authorization = "Bearer $token" }

            $newStatus = $r.status
            if ($newStatus -ne $job.LastStatus) {
                Write-Log "[$($now.ToString('HH:mm:ss'))] jobId=$($job.JobId) status=$newStatus"
                $job.Transitions += [pscustomobject]@{ Time = $now; Status = $newStatus }
                $job.LastStatus = $newStatus

                if ($newStatus -eq "PROCESSING" -and -not $job.StartedAt) {
                    $job.StartedAt = $now
                }
            }
            if ($terminalStates -contains $newStatus) {
                $job.Terminal    = $true
                $job.FinalStatus = $newStatus
                $job.CompletedAt = $now
            }
        } catch {
            Write-Log "GET failed for $($job.JobId): $_" Yellow
        }
    }
}

Write-Log ""
Write-Log "=== DB timestamps ===" Cyan

$idList = ($jobs | ForEach-Object { "'$($_.JobId)'" }) -join ","
$sql = "SELECT id, status, created_at, started_at, completed_at, retry_count FROM generations WHERE id IN ($idList) ORDER BY created_at;"

$dbOutput = $null
if ($PgMode -eq "docker") {
    try {
        $dbOutput = & docker exec $PgContainer psql -U $PgUser -d $PgDatabase -t -A -F'|' -c $sql 2>&1
    } catch {
        Write-Log "docker exec failed: $_" Yellow
        $dbOutput = $null
    }
} else {
    try {
        $env:PGPASSWORD = $Password
        $dbOutput = & psql -h $PgHost -p $PgPort -U $PgUser -d $PgDatabase -t -A -F'|' -c $sql 2>&1
        Remove-Item Env:\PGPASSWORD
    } catch {
        Write-Log "psql failed: $_" Yellow
        $dbOutput = $null
    }
}

$dbRows = @{}
if ($dbOutput) {
    foreach ($line in $dbOutput) {
        $s = "$line".Trim()
        if ([string]::IsNullOrWhiteSpace($s)) { continue }
        $parts = $s -split '\|'
        if ($parts.Length -ge 6) {
            $dbRows[$parts[0]] = [pscustomobject]@{
                Status      = $parts[1]
                CreatedAt   = $parts[2]
                StartedAt   = $parts[3]
                CompletedAt = $parts[4]
                RetryCount  = $parts[5]
            }
        }
    }
}

Write-Log ""
Write-Log "=== Per-job summary ===" Cyan
$totals = @()
foreach ($job in $jobs) {
    $processingEntry = $job.Transitions | Where-Object { $_.Status -eq "PROCESSING" } | Select-Object -First 1
    $terminalEntry   = $job.Transitions | Where-Object { $terminalStates -contains $_.Status } | Select-Object -First 1

    $queuedEnd = $null
    if ($processingEntry) { $queuedEnd = $processingEntry.Time }
    elseif ($terminalEntry) { $queuedEnd = $terminalEntry.Time }

    $queuedSec = $null
    if ($queuedEnd -and $job.CreatedAt) {
        $queuedSec = [math]::Round(($queuedEnd - $job.CreatedAt).TotalSeconds, 1)
    }

    $processingSec = $null
    if ($job.StartedAt -and $job.CompletedAt) {
        $processingSec = [math]::Round(($job.CompletedAt - $job.StartedAt).TotalSeconds, 1)
    }

    $totalSec = $null
    if ($job.CompletedAt) {
        $totalSec = [math]::Round(($job.CompletedAt - $job.CreatedAt).TotalSeconds, 1)
    }

    $totals += [pscustomobject]@{
        JobId         = $job.JobId
        FinalStatus   = $job.FinalStatus
        QueuedSec     = $queuedSec
        ProcessingSec = $processingSec
        TotalSec      = $totalSec
    }

    Write-Log ""
    Write-Log "jobId=$($job.JobId)"
    Write-Log "  prompt:              $($job.Prompt)"
    Write-Log "  final status:        $($job.FinalStatus)"
    Write-Log "  time in QUEUED:      $queuedSec sec"
    Write-Log "  time in PROCESSING:  $processingSec sec"
    Write-Log "  total:               $totalSec sec"

    if ($dbRows.ContainsKey($job.JobId)) {
        $db = $dbRows[$job.JobId]
        Write-Log "  DB status:           $($db.Status)"
        Write-Log "  DB created_at:       $($db.CreatedAt)"
        Write-Log "  DB started_at:       $($db.StartedAt)"
        Write-Log "  DB completed_at:     $($db.CompletedAt)"
        Write-Log "  DB retry_count:      $($db.RetryCount)"
    } else {
        Write-Log "  DB:                  not found" Yellow
    }
}

Write-Log ""
Write-Log "=== Global report ===" Cyan

$completed = @($jobs | Where-Object { $_.FinalStatus -eq "COMPLETED" }).Count
$failed    = @($jobs | Where-Object { $_.FinalStatus -eq "FAILED" }).Count
$cancelled = @($jobs | Where-Object { $_.FinalStatus -eq "CANCELLED" }).Count
$stuck     = @($jobs | Where-Object { -not $_.Terminal }).Count

Write-Log "Total jobs:  $($jobs.Count)"
Write-Log "COMPLETED:   $completed"
Write-Log "FAILED:      $failed"
Write-Log "CANCELLED:   $cancelled"
Write-Log "STUCK:       $stuck"

$finished = $totals | Where-Object { $_.TotalSec -ne $null }
if ($finished.Count -gt 0) {
    $avgTotal = [math]::Round((($finished | Measure-Object -Property TotalSec -Average).Average), 1)
    Write-Log "Avg total:   $avgTotal sec"
}
$queuedFinished = $totals | Where-Object { $_.QueuedSec -ne $null }
if ($queuedFinished.Count -gt 0) {
    $avgQueued = [math]::Round((($queuedFinished | Measure-Object -Property QueuedSec -Average).Average), 1)
    Write-Log "Avg QUEUED:  $avgQueued sec"
}
$procFinished = $totals | Where-Object { $_.ProcessingSec -ne $null }
if ($procFinished.Count -gt 0) {
    $avgProc = [math]::Round((($procFinished | Measure-Object -Property ProcessingSec -Average).Average), 1)
    Write-Log "Avg PROC:    $avgProc sec"
}

if ($LogFile) {
    Write-Host ""
    Write-Host "Log saved to: $LogFile" -ForegroundColor Green
}