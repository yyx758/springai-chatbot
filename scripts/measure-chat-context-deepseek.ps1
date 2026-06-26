param(
    [string]$EnvFile = ".env",
    [string]$BaseUrl = "https://api.deepseek.com",
    [string]$Model = "deepseek-chat",
    [int]$Rounds = 3,
    [int]$MaxTokens = 48
)

$ErrorActionPreference = "Stop"

function Import-DotEnv {
    param([string]$Path)
    $values = @{}
    if (!(Test-Path -Path $Path)) {
        throw "Env file not found: $Path"
    }
    Get-Content -Path $Path | ForEach-Object {
        $line = $_.Trim()
        if (!$line -or $line.StartsWith("#")) {
            return
        }
        if ($line -match "^([^=]+)=(.*)$") {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$name] = $value
        }
    }
    return $values
}

function New-HistoryMessages {
    $messages = New-Object System.Collections.Generic.List[object]
    $messages.Add(@{
            role    = "system"
            content = "You are AI Studio's code review agent. Follow the safety boundary and answer only from the available context."
        })

    $summary = "Earlier conversation summary: the user is turning AI Studio into a code review expert agent. Key constraints include Gateway as the only production entry, workspace file changes must go through Pending Action, GitReviewService must remain read-only, and the context module should control token cost while preserving durable constraints."
    $relevant = @"
Relevant earlier snippets:
- User: Explain the tradeoffs of Redis cache and context token budget. Avoid privilege bypass, avoid writing real Git directly, and include verification.
  Assistant: Keep user isolation, read-only previews, Pending Action confirmation, structured logs, and repeatable tests.
- User: Explain code review Agent pending action safety.
  Assistant: Patch preview is read-only, apply-request only creates a PENDING action, and confirm is the only step that writes workspace files.
"@

    $messages.Add(@{ role = "system"; content = $summary })
    $messages.Add(@{ role = "system"; content = $relevant })

    for ($i = 91; $i -le 100; $i++) {
        if ($i % 4 -eq 0) {
            $topic = "Redis cache and context token budget"
        }
        elseif ($i % 4 -eq 1) {
            $topic = "Kafka outbox and DLT retry"
        }
        elseif ($i % 4 -eq 2) {
            $topic = "code review Agent pending action safety"
        }
        else {
            $topic = "Gateway relative API path and workspace sync"
        }

        $messages.Add(@{
                role    = "user"
                content = "Turn $i question: explain the design tradeoffs of $topic. Avoid privilege bypass, avoid writing real Git directly, and include verification."
            })
        $messages.Add(@{
                role    = "assistant"
                content = "Turn $i answer: for $topic, keep user isolation, read-only previews, Pending Action confirmation, structured logs, and repeatable tests."
            })
    }

    return , $messages
}

function Invoke-ChatCompletion {
    param(
        [string]$Name,
        [System.Collections.Generic.List[object]]$Messages,
        [string]$ApiKey
    )

    $payload = @{
        model       = $Model
        stream      = $false
        messages    = $Messages
        temperature = 0
        max_tokens  = $MaxTokens
    } | ConvertTo-Json -Depth 20

    $headers = @{
        Authorization = "Bearer $ApiKey"
        "Content-Type" = "application/json"
    }

    $wall = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod -Uri "$BaseUrl/chat/completions" -Method Post -Headers $headers -Body $payload -TimeoutSec 180
    $wall.Stop()

    $text = ""
    if ($response.choices -and $response.choices.Count -gt 0) {
        $text = [string]$response.choices[0].message.content
    }
    $preview = ($text -replace "`r?`n", " ")
    if ($preview.Length -gt 80) {
        $preview = $preview.Substring(0, 80)
    }

    $usage = $response.usage
    [pscustomobject]@{
        name                      = $Name
        wall_ms                   = [math]::Round($wall.Elapsed.TotalMilliseconds, 2)
        prompt_tokens             = $usage.prompt_tokens
        completion_tokens         = $usage.completion_tokens
        total_tokens              = $usage.total_tokens
        prompt_cache_hit_tokens   = $usage.prompt_cache_hit_tokens
        prompt_cache_miss_tokens  = $usage.prompt_cache_miss_tokens
        response_preview          = $preview
    }
}

function Get-Average {
    param(
        [object[]]$Rows,
        [string]$Property
    )
    [math]::Round((($Rows | Measure-Object $Property -Average).Average), 2)
}

$envValues = Import-DotEnv -Path $EnvFile
$apiKey = $envValues["DEEPSEEK_API_KEY"]
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw "DEEPSEEK_API_KEY is empty in $EnvFile"
}

$rag = "RAG knowledge context: production user traffic must enter only through Gateway :9000; do not expose chatbot-service:8080 or file-service:8081; file changes require Pending Action confirmation; do not auto commit or push."
$current = "Based on the context above, explain in three points how the chat context module reduces token cost while preserving safety boundaries."

$results = New-Object System.Collections.Generic.List[object]
for ($round = 1; $round -le $Rounds; $round++) {
    $before = New-HistoryMessages
    $before.Insert(1, @{ role = "system"; content = $rag })
    $before.Add(@{ role = "user"; content = $current })
    $results.Add((Invoke-ChatCompletion -Name "before_$round" -Messages $before -ApiKey $apiKey))

    $after = New-HistoryMessages
    $after.Add(@{ role = "system"; content = $rag })
    $after.Add(@{ role = "user"; content = $current })
    $results.Add((Invoke-ChatCompletion -Name "after_$round" -Messages $after -ApiKey $apiKey))
}

$results | Format-Table -AutoSize

$beforeRows = @($results | Where-Object { $_.name -like "before_*" })
$afterRows = @($results | Where-Object { $_.name -like "after_*" })

[pscustomobject]@{
    provider                         = "deepseek-openai-compatible"
    model                            = $Model
    rounds                           = $Rounds
    before_prompt_tokens_avg         = Get-Average $beforeRows "prompt_tokens"
    after_prompt_tokens_avg          = Get-Average $afterRows "prompt_tokens"
    before_completion_tokens_avg     = Get-Average $beforeRows "completion_tokens"
    after_completion_tokens_avg      = Get-Average $afterRows "completion_tokens"
    before_total_tokens_avg          = Get-Average $beforeRows "total_tokens"
    after_total_tokens_avg           = Get-Average $afterRows "total_tokens"
    before_cache_hit_tokens_avg      = Get-Average $beforeRows "prompt_cache_hit_tokens"
    after_cache_hit_tokens_avg       = Get-Average $afterRows "prompt_cache_hit_tokens"
    before_cache_miss_tokens_avg     = Get-Average $beforeRows "prompt_cache_miss_tokens"
    after_cache_miss_tokens_avg      = Get-Average $afterRows "prompt_cache_miss_tokens"
    before_wall_ms_avg               = Get-Average $beforeRows "wall_ms"
    after_wall_ms_avg                = Get-Average $afterRows "wall_ms"
} | Format-List
