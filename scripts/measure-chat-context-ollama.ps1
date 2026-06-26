param(
    [string]$BaseUrl = "http://localhost:11434",
    [string]$Model = "qwen2.5:0.5b",
    [int]$Rounds = 3,
    [int]$NumPredict = 48
)

$ErrorActionPreference = "Stop"
$ChatUri = "$BaseUrl/api/chat"

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

function Invoke-OllamaChat {
    param(
        [string]$Name,
        [System.Collections.Generic.List[object]]$Messages
    )

    $payload = @{
        model    = $Model
        stream   = $false
        messages = $Messages
        options  = @{
            temperature = 0
            num_predict = $NumPredict
        }
    } | ConvertTo-Json -Depth 20

    $wall = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod -Uri $ChatUri -Method Post -Body $payload -ContentType "application/json" -TimeoutSec 180
    $wall.Stop()

    $preview = ($response.message.content -replace "`r?`n", " ")
    if ($preview.Length -gt 80) {
        $preview = $preview.Substring(0, 80)
    }

    [pscustomobject]@{
        name              = $Name
        wall_ms           = [math]::Round($wall.Elapsed.TotalMilliseconds, 2)
        total_ms          = [math]::Round(($response.total_duration / 1000000), 2)
        prompt_tokens     = $response.prompt_eval_count
        prompt_eval_ms    = [math]::Round(($response.prompt_eval_duration / 1000000), 2)
        completion_tokens = $response.eval_count
        completion_ms     = [math]::Round(($response.eval_duration / 1000000), 2)
        response_preview  = $preview
    }
}

function Get-Average {
    param(
        [object[]]$Rows,
        [string]$Property
    )
    [math]::Round((($Rows | Measure-Object $Property -Average).Average), 2)
}

$tags = Invoke-RestMethod -Uri "$BaseUrl/api/tags" -TimeoutSec 15
$availableModels = @($tags.models | ForEach-Object { $_.name })
if ($availableModels -notcontains $Model) {
    throw "Ollama model '$Model' is not installed. Available models: $($availableModels -join ', ')"
}

$rag = "RAG knowledge context: production user traffic must enter only through Gateway :9000; do not expose chatbot-service:8080 or file-service:8081; file changes require Pending Action confirmation; do not auto commit or push."
$current = "Based on the context above, explain in three points how the chat context module reduces token cost while preserving safety boundaries."

Invoke-OllamaChat -Name "warmup" -Messages ([System.Collections.Generic.List[object]]@(
        @{ role = "system"; content = "You are a test assistant." },
        @{ role = "user"; content = "Reply only OK." }
    )) | Out-Null

$results = New-Object System.Collections.Generic.List[object]
for ($round = 1; $round -le $Rounds; $round++) {
    $before = New-HistoryMessages
    $before.Insert(1, @{ role = "system"; content = $rag })
    $before.Add(@{ role = "user"; content = $current })
    $results.Add((Invoke-OllamaChat -Name "before_$round" -Messages $before))

    $after = New-HistoryMessages
    $after.Add(@{ role = "system"; content = $rag })
    $after.Add(@{ role = "user"; content = $current })
    $results.Add((Invoke-OllamaChat -Name "after_$round" -Messages $after))
}

$results | Format-Table -AutoSize

$beforeRows = @($results | Where-Object { $_.name -like "before_*" })
$afterRows = @($results | Where-Object { $_.name -like "after_*" })

[pscustomobject]@{
    model                         = $Model
    rounds                        = $Rounds
    before_prompt_tokens_avg      = Get-Average $beforeRows "prompt_tokens"
    after_prompt_tokens_avg       = Get-Average $afterRows "prompt_tokens"
    before_prompt_eval_ms_avg     = Get-Average $beforeRows "prompt_eval_ms"
    after_prompt_eval_ms_avg      = Get-Average $afterRows "prompt_eval_ms"
    before_completion_tokens_avg  = Get-Average $beforeRows "completion_tokens"
    after_completion_tokens_avg   = Get-Average $afterRows "completion_tokens"
    before_total_ms_avg           = Get-Average $beforeRows "total_ms"
    after_total_ms_avg            = Get-Average $afterRows "total_ms"
    before_wall_ms_avg            = Get-Average $beforeRows "wall_ms"
    after_wall_ms_avg             = Get-Average $afterRows "wall_ms"
} | Format-List
