$path = "src/main/resources/static/app.js"
$c = Get-Content $path -Raw
Write-Host "Length:" $c.Length
Write-Host "First 300:" $c.Substring(0, [System.Math]::Min(300, $c.Length))
Write-Host "Last 300:" $c.Substring([System.Math]::Max(0, $c.Length - 300))
