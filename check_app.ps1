$ErrorActionPreference = "Continue"
function Test-Endpoint {
    param([string]$TargetUrl, [string]$TargetLabel)
    Write-Output ("=== " + $TargetLabel + ": " + $TargetUrl + " ===")
    try {
        $r = Invoke-WebRequest $TargetUrl -TimeoutSec 10 -UseBasicParsing
        Write-Output ("STATUS: " + $r.StatusCode)
        Write-Output ("BODY: " + $r.Content.Substring(0, [Math]::Min(300, $r.Content.Length)))
    } catch {
        if ($_.Exception.Response) {
            $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $sr.BaseStream.Position = 0
            $sr.DiscardBufferedData()
            Write-Output ("STATUS: " + $_.Exception.Response.StatusCode.value__)
            $body = $sr.ReadToEnd()
            Write-Output ("BODY: " + $body.Substring(0, [Math]::Min(500, $body.Length)))
        } else {
            Write-Output ("ERROR: " + $_.Exception.Message)
        }
    }
    Write-Output ""
}

Test-Endpoint -TargetUrl "http://localhost:8080/" -TargetLabel "Root"
Test-Endpoint -TargetUrl "http://localhost:8080/actuator/health" -TargetLabel "Health"
