<#
  千问接入冒烟验证（配好 DASHSCOPE_API_KEY 后运行）

  为什么需要它：真实模型的行为只能实测，而实测最容易出问题的不是"能不能调通"，
  而是四件必须先确认、否则后面全白做的事：
    1. 文本模型返回的到底是不是可解析的 JSON（不是就没法进校验链路）
    2. qwen-vl-ocr 到底返不返回文字坐标（项目文档记录的未知项 V1）
    3. ASR 的句级时间戳字段名与单位
    4. 临时文件上传能不能让内网物料被模型读到

  这四项各自失败的方式都不一样，逐个手工试很容易漏。所以做成一条命令，
  一次跑完并把原始证据打出来。

  用法：
    # 1) 先在设置界面或 deploy/.env 里配好 Key 并切到 dashscope
    # 2) 然后：
    pwsh -File deploy/ai-smoke.ps1
#>

param(
  [string]$Base = "http://localhost:8080",
  [long]$UserId = 1
)

$ErrorActionPreference = "Continue"

$perms = @(
  'case.view','case.list','case.create','case.upload','case.parse_retry',
  'case.requirement_confirm','case.start_initial_review',
  'risk.view','version.view',
  'assistant.use','assistant.file_upload','admin.config','report.view'
) -join ','

function Api([string]$method, [string]$path, $body) {
  $req = [System.Net.HttpWebRequest]::Create("$Base$path")
  $req.Method = $method
  $req.Headers.Add('X-Actor-User-Id', "$UserId")
  $req.Headers.Add('X-Actor-Permissions', $perms)
  if ($null -ne $body) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress))
    $req.ContentType = 'application/json; charset=utf-8'
    $req.ContentLength = $bytes.Length
    $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
  }
  $resp = $null
  try { $resp = $req.GetResponse() } catch [System.Net.WebException] {
    $resp = $_.Exception.Response
    if (-not $resp) { return [pscustomobject]@{ __err = $_.Exception.Message } }
  }
  # 显式按 UTF-8 解码：Spring 不带 charset 时 PowerShell 5.1 会按 Latin-1 解，中文全乱
  $ms = New-Object System.IO.MemoryStream
  $resp.GetResponseStream().CopyTo($ms)
  $text = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
  $status = [int]$resp.StatusCode
  $resp.Close()
  if ($status -ge 400) { return [pscustomobject]@{ __httpStatus = $status; __error = $text } }
  if ([string]::IsNullOrWhiteSpace($text)) { return [pscustomobject]@{ code = 0; data = $null } }
  return ($text | ConvertFrom-Json)
}

function Upload([long]$caseId, [string]$file, [string]$reason) {
  $argv = @('-s','-X','POST',"$Base/api/intake/materials/upload",
    '-H',"X-Actor-User-Id: $UserId",'-H',"X-Actor-Permissions: $perms",
    '-F',"caseId=$caseId",'-F',"file=@$file")
  if ($reason) { $argv += @('-F',"uploadReason=$reason") }
  return ((& curl.exe @argv) | ConvertFrom-Json)
}

$script:pass = 0
$script:fail = 0
function Check([string]$name, [bool]$cond, [string]$detail = "") {
  if ($cond) { $script:pass++; Write-Host "  [PASS] $name" -ForegroundColor Green }
  else { $script:fail++; Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red }
}

$tmp = Join-Path $env:TEMP "gw-ai-smoke-$(Get-Date -Format 'HHmmss')"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

# ══════════════════════════════════════════════════════════════════════════
Write-Host "`n=== 0. 前提：必须已切到 dashscope 且配好 Key ===" -ForegroundColor Cyan
$st = Api GET '/api/ai/status' $null
Write-Host "  provider=$($st.data.provider)  keyConfigured=$($st.data.apiKeyConfigured)"
if ($st.data.provider -ne 'dashscope') {
  Write-Host "`n  当前不是 dashscope，后续验证无意义。请先在左下角设置里切换供应商并填 Key。" -ForegroundColor Yellow
  exit 1
}
if (-not $st.data.apiKeyConfigured) {
  Write-Host "`n  未配置 API Key，后续验证必然失败。请先在设置里填写。" -ForegroundColor Yellow
  exit 1
}

# ══════════════════════════════════════════════════════════════════════════
Write-Host "`n=== 1. 文本模型：助手对话（验证返回真实模型输出）===" -ForegroundColor Cyan
$chat = Api POST '/api/assistant/chat' @{ message = '本产品行业第一，100%保障安全。请指出其中的广告合规风险。' }
if ($chat.__httpStatus) {
  Check "助手对话可调用" $false ($chat.__error)
  Write-Host "`n  文本模型不通，后续步骤大概率同样失败，但继续跑完以便一次看清全部问题。" -ForegroundColor Yellow
} else {
  Check "助手对话可调用" ($chat.code -eq 0)
  $reply = [string]$chat.data.reply
  Write-Host ("  回答前 160 字：" + $reply.Substring(0, [Math]::Min(160, $reply.Length)))
  Check "返回内容具备模型特征（非规则模板固定句式）" `
    (-not ($reply -match '^本句话命中')) "疑似仍是规则实现输出"
  Check "已识别出风险点" ($chat.data.risks.Count -gt 0) ("risks=" + $chat.data.risks.Count)
}

# ══════════════════════════════════════════════════════════════════════════
Write-Host "`n=== 2. 临时文件上传（内网物料能否送达模型服务）===" -ForegroundColor Cyan
Write-Host "  说明：这一步不单独测，而是由第 3 步的图片 OCR 与第 4 步的音频转写间接验证——"
Write-Host "        那两步都必须先把文件送到模型服务能取到的位置。"

# ══════════════════════════════════════════════════════════════════════════
Write-Host "`n=== 3. 图片 OCR：文字识别 + 坐标形态（未知项 V1）===" -ForegroundColor Cyan
$png = Join-Path $tmp 'poster.png'
try {
  Add-Type -AssemblyName System.Drawing
  $bmp = New-Object System.Drawing.Bitmap 900, 520
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::White)
  $fontBig = New-Object System.Drawing.Font('Microsoft YaHei', 40, [System.Drawing.FontStyle]::Bold)
  $fontSm = New-Object System.Drawing.Font('Microsoft YaHei', 18)
  $brush = [System.Drawing.Brushes]::Black
  $g.DrawString('行业第一 品质最优', $fontBig, $brush, 60, 70)
  $g.DrawString('100%保障安全 续航可达1000km', $fontBig, $brush, 60, 170)
  $g.DrawString('本广告数据来自第三方检测报告，报告编号 2026-0831', $fontSm, $brush, 60, 300)
  $g.DrawString('限时立减5000元', $fontBig, $brush, 60, 370)
  $g.Dispose(); $bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  Write-Host "  已生成测试海报：$png"
} catch {
  Write-Host "  生成测试图片失败（$($_.Exception.Message)），跳过 OCR 验证" -ForegroundColor Yellow
}

if (Test-Path $png) {
  $case = Api POST '/api/intake/cases' @{ projectId = 1; name = '冒烟验证 - 图片 OCR' }
  $caseId = $case.data.id
  $up = Upload $caseId $png '冒烟验证'
  Check "图片上传成功" ($up.code -eq 0) ($up | ConvertTo-Json -Compress)

  $parse = Api POST "/api/intake/materials/$($up.data.materialId)/parse" $null
  Check "图片解析产出锚点" ($parse.code -eq 0 -and $parse.data.anchorCount -gt 0) `
    ("锚点=" + $parse.data.anchorCount + " err=" + $parse.data.parseErrorMessage)
  Write-Host "  锚点数=$($parse.data.anchorCount) 解析状态=$($parse.data.parseStatus)"

  # 关键：坐标形态。决定 V1 的结论，也决定界面上是"精确框选"还是"大致区域"
  $anchors = Api GET "/api/intake/materials/$($up.data.materialId)/versions" $null
  Write-Host "  (锚点明细需查库确认坐标形态，见脚本末尾的 SQL)" -ForegroundColor DarkGray
}

# ══════════════════════════════════════════════════════════════════════════
Write-Host "`n=== 4. 语音转写：时间戳字段与单位 ===" -ForegroundColor Cyan
$wav = Join-Path $tmp 'speech.wav'
$ttsOk = $false
try {
  Add-Type -AssemblyName System.Speech
  $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
  $voices = $synth.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }
  Write-Host "  可用语音：$($voices -join ', ')"
  $zh = $voices | Where-Object { $_ -match 'Chinese|Huihui|Yaoyao|Kangkang|Xiaoxiao' } | Select-Object -First 1
  if ($zh) { $synth.SelectVoice($zh) }
  $synth.Rate = -1
  $synth.SetOutputToWaveFile($wav)
  $synth.Speak('本产品行业第一，续航可达一千公里，限时立减五千元。')
  $synth.Dispose()
  $ttsOk = (Test-Path $wav) -and ((Get-Item $wav).Length -gt 1000)
  if ($ttsOk) { Write-Host ("  已用本机 TTS 生成测试语音：{0} KB" -f [Math]::Round((Get-Item $wav).Length/1KB)) }
} catch {
  Write-Host "  本机 TTS 不可用（$($_.Exception.Message)）" -ForegroundColor Yellow
}
if (-not $ttsOk) {
  Write-Host "  跳过 ASR 验证。要测 ASR 请准备一个真人说话的 wav/mp3 后重跑，或手动上传视频物料。" -ForegroundColor Yellow
} else {
  $case2 = Api POST '/api/intake/cases' @{ projectId = 1; name = '冒烟验证 - 语音转写' }
  $up2 = Upload $case2.data.id $wav '冒烟验证'
  Check "音频上传成功" ($up2.code -eq 0) ($up2 | ConvertTo-Json -Compress)
  $p2 = Api POST "/api/intake/materials/$($up2.data.materialId)/parse" $null
  Check "语音转写产出锚点" ($p2.code -eq 0 -and $p2.data.anchorCount -gt 0) `
    ("锚点=" + $p2.data.anchorCount + " err=" + $p2.data.parseErrorMessage)
  Write-Host "  锚点数=$($p2.data.anchorCount) 解析状态=$($p2.data.parseStatus)"
}

# ══════════════════════════════════════════════════════════════════════════
Write-Host "`n=== 5. 语义检索（embedding + rerank）===" -ForegroundColor Cyan
Write-Host "  这一步由第 3 步的初审间接验证：若检索不可用，风险会因"无适用依据"全部转人工。" -ForegroundColor DarkGray
if ($caseId) {
  $rr = Api POST "/api/intake/cases/$caseId/initial-review" $null
  Check "启动 AI 初审" ($rr.code -eq 0) ($rr | ConvertTo-Json -Compress)
  $risks = Api GET "/api/feedback/risks?caseId=$caseId" $null
  Write-Host "  风险数=$($risks.data.Count)"
  $withBasis = @($risks.data | Where-Object { $_.basisText })
  Check "风险带出了法条依据（说明检索链路通）" ($withBasis.Count -gt 0 -or $risks.data.Count -eq 0) `
    ("带依据 " + $withBasis.Count + "/" + $risks.data.Count)
  foreach ($r in $risks.data | Select-Object -First 5) {
    Write-Host ("    {0}  {1}  「{2}」" -f $r.riskNo, $r.riskLevel, $r.riskText)
  }
}

# ══════════════════════════════════════════════════════════════════════════
Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host ("  通过 " + $script:pass + " 项，失败 " + $script:fail + " 项")
Write-Host "============================================" -ForegroundColor Cyan

Write-Host @"

【需要人工看的原始证据】下面两条 SQL 决定两个未知项的结论：

-- V1：OCR 是否返回文字坐标（看 locator 里有没有 bbox，还是只有 localized:false）
SELECT m.name, a.anchor_type, a.locator, LEFT(a.text, 30) AS text
  FROM evidence_anchor a JOIN material_version v ON v.id = a.material_version_id
  JOIN material m ON m.id = v.material_id
 WHERE m.name LIKE '%.png' ORDER BY a.id DESC LIMIT 20;

-- ASR 时间戳单位（应为毫秒整数；若数量级是秒则说明解析错了单位）
SELECT m.name, JSON_EXTRACT(a.locator, `$.begin_ms`) AS begin_ms,
       JSON_EXTRACT(a.locator, `$.end_ms`) AS end_ms, LEFT(a.text, 40) AS text
  FROM evidence_anchor a JOIN material_version v ON v.id = a.material_version_id
  JOIN material m ON m.id = v.material_id
 WHERE m.name LIKE '%.wav' OR m.name LIKE '%.mp3' ORDER BY a.id DESC LIMIT 10;

-- 调用留痕（模型型号、耗时）：确认走的确实是千问而不是规则实现
docker logs gw-audit-api --since 10m 2>&1 | Select-String 'dashscope'
"@
