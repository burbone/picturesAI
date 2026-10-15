$ErrorActionPreference = 'Stop'

$KEYCLOAK   = 'http://localhost:8090'
$REALM      = 'ai-gallery'
$CLIENT_ID  = 'api-gateway'
$CLIENT_SEC = 'picturesAI'
$USER       = 'user'
$PASS       = 'picturesAI'
$WS_BASE    = 'ws://localhost:8083'
$KAFKA_CT   = 'picturesAI-kafka'
$KAFKA_BS   = 'localhost:9092'

Write-Host '=== 1. Get token ===' -ForegroundColor Cyan
$body = @{
    grant_type    = 'password'
    client_id     = $CLIENT_ID
    client_secret = $CLIENT_SEC
    username      = $USER
    password      = $PASS
}
$resp = Invoke-RestMethod -Method Post `
    -Uri "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body $body
$token = $resp.access_token
Write-Host "OK: $($token.Substring(0,40))..."

Write-Host '=== 2. Extract sub ===' -ForegroundColor Cyan
$parts = $token.Split('.')
$payload = $parts[1].Replace('-','+').Replace('_','/')
while ($payload.Length % 4) { $payload += '=' }
$sub = ([System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json).sub
Write-Host "sub=$sub"

Write-Host '=== 3. Open WS (background) ===' -ForegroundColor Cyan
$listenJob = Start-Job -ScriptBlock {
    param($uri)
    try {
        $ws = New-Object System.Net.WebSockets.ClientWebSocket
        $ws.ConnectAsync([Uri]$uri, [System.Threading.CancellationToken]::None).Wait()
        Write-Output "OPENED state=$($ws.State)"
        $buffer = New-Object byte[] 8192
        while ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$buffer)
            $res = $ws.ReceiveAsync($seg, [System.Threading.CancellationToken]::None).Result
            if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                Write-Output "CLOSED code=$([int]$ws.CloseStatus) reason=$($ws.CloseStatusDescription)"
                break
            }
            $text = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $res.Count)
            Write-Output "MSG $text"
        }
    } catch {
        Write-Output "ERR $_"
    }
} -ArgumentList "$WS_BASE/ws/notifications?token=$token"

Start-Sleep -Seconds 2
Receive-Job $listenJob

Write-Host '=== 4. Kafka: generation.completed ===' -ForegroundColor Cyan
$genId = [guid]::NewGuid().ToString()
$payload1 = '{"generationId":"' + $genId + '","userId":"' + $sub + '","imageUrl":"http://img/x.png","thumbnailUrl":"http://img/t.png","provider":"openai","completedAt":"2026-01-01T00:00:00Z"}'
$payload1 | docker exec -i $KAFKA_CT kafka-console-producer --bootstrap-server $KAFKA_BS --topic generation.completed
Start-Sleep -Seconds 3
Receive-Job $listenJob

Write-Host '=== 5. Kafka: user.deleted ===' -ForegroundColor Cyan
$payload2 = '{"userId":"' + $sub + '","keycloakId":"kc-1","deletedAt":"2026-01-01T00:00:00Z"}'
$payload2 | docker exec -i $KAFKA_CT kafka-console-producer --bootstrap-server $KAFKA_BS --topic user.deleted
Start-Sleep -Seconds 3
Receive-Job $listenJob

Stop-Job $listenJob -ErrorAction SilentlyContinue
Remove-Job $listenJob -Force -ErrorAction SilentlyContinue

Write-Host '=== done ===' -ForegroundColor Green