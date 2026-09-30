$path = "src/main/resources/static/app.js"
$lines = Get-Content $path
$output = New-Object System.Collections.Generic.List[string]
$inOrphan = $false
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    # Detect start of orphaned block (line 614, 1-based = index 613)
    if ($i -eq 613 -and $line.TrimStart().StartsWith("categoryCurrentQuery")) {
        $inOrphan = $true
    }
    if ($inOrphan) {
        # End when we hit function showSection
        if ($line.StartsWith("    function showSection")) {
            $inOrphan = $false
            continue
        }
        continue
    }
    $output.Add($line)
}
$output | Set-Content $path -NoNewline
Write-Host "Done, lines: $($output.Count)"
