<#
  用无头 Chrome 给工作台各模块截图。

  用途：验证"改完之后界面到底长什么样"——只跑类型检查不足以发现
  布局塌陷、层级错位、空态没渲染这类问题，必须真看一眼。

  前置：Vite 开发服务器已在 5173 运行（cd gw-web; npm run dev）。

  用法：
    pwsh -File deploy/shoot.ps1                       # 四个模块各截一张
    pwsh -File deploy/shoot.ps1 -Route feedback       # 只截反馈区
#>

param(
  [string]$Route = '',
  [int]$Width = 1680,
  [int]$Height = 1080,
  # 等待前端把接口数据渲染出来；太短会截到加载中状态
  [int]$BudgetMs = 9000,
  [string]$BaseUrl = 'http://127.0.0.1:5173'
)

$ErrorActionPreference = 'Stop'

$chrome = @(
  'C:\Program Files\Google\Chrome\Application\chrome.exe',
  'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
  "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $chrome) { throw '未找到 Chrome，无法截图' }

$root = Split-Path -Parent $PSScriptRoot
$shotDir = Join-Path $root 'deploy\shots'
New-Item -ItemType Directory -Path $shotDir -Force | Out-Null

$all = @(
  @{ route = 'intake';      file = 'intake.png' },
  @{ route = 'feedback';    file = 'feedback.png' },
  @{ route = 'final-review'; file = 'final-review.png' },
  @{ route = 'assistant';   file = 'assistant.png' }
)

$targets = if ($Route) { $all | Where-Object { $_.route -eq $Route } } else { $all }
if (-not $targets) { throw "未知模块：$Route" }

foreach ($t in $targets) {
  $out = Join-Path $shotDir $t.file
  $url = "$BaseUrl/#/$($t.route)"
  # 已存在则先删：Chrome 在文件被占用时会静默不写，导致拿到旧图
  if (Test-Path $out) { Remove-Item $out -Force }
  & $chrome --headless=new --disable-gpu --hide-scrollbars --no-first-run `
    --window-size="$Width,$Height" --screenshot="$out" `
    --virtual-time-budget=$BudgetMs $url 2>$null | Out-Null

  if (Test-Path $out) {
    $kb = [math]::Round((Get-Item $out).Length / 1KB)
    Write-Host ("  {0,-14} -> {1} ({2} KB)" -f $t.route, $t.file, $kb) -ForegroundColor Green
  } else {
    Write-Host ("  {0,-14} 截图失败" -f $t.route) -ForegroundColor Red
  }
}
