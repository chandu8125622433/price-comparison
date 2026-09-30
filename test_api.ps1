$ErrorActionPreference = "Continue"
function Test-Search {
    param([string]$Query)
    Write-Output "=== $Query ==="
    $timeout = 120
    try {
        $url = "http://localhost:8080/api/products/category-search?q=" + [System.Uri]::EscapeDataString($Query)
        $r = Invoke-WebRequest $url -TimeoutSec $timeout -UseBasicParsing
        $json = $r.Content | ConvertFrom-Json
        Write-Output "STATUS: $($r.StatusCode)"
        Write-Output "TOTAL: $($json.totalProducts)"
        Write-Output "CATEGORY: $($json.category)"
        if ($json.products -and $json.products.Count -gt 0) {
            $vijayCount = ($json.products | Where-Object { $_.retailers -contains "Vijay Sales" }).Count
            Write-Output "VIJAY: $vijayCount"
            $imageCount = ($json.products | Where-Object { $_.imageUrl -and $_.imageUrl -ne "" }).Count
            $priceCount = ($json.products | Where-Object { $_.lowestPrice -and $_.lowestPrice -ne "" }).Count
            $urlCount = ($json.products | Where-Object { $_.productUrl -and $_.productUrl -ne "" }).Count
            Write-Output "IMAGES: $imageCount PRICES: $priceCount URLS: $urlCount"
            # Check for wallmount issues (Issue 1)
            $wallmount = @()
            foreach ($p in $json.products) {
                $name = $p.name.ToLower()
                if ($name -match "wallmount|wall mount" -and $name -match "tv") {
                    $wallmount += $p.name
                }
            }
            if ($wallmount.Count -gt 0) { Write-Output "WALLMOUNT ISSUE: $($wallmount.Count) products" }
            # Check for graphic tablets (Issue 2)
            $graphicTablets = @()
            foreach ($p in $json.products) {
                $name = $p.name.ToLower()
                if ($name -match "xp-pen|wacom|huion" -and $json.category -eq "tablet") {
                    $graphicTablets += $p.name
                }
            }
            if ($graphicTablets.Count -gt 0) { Write-Output "GRAPHIC TABLET ISSUE: $($graphicTablets.Count) products" }
            # Check for camera accessories (Issue 3)
            $camAcc = @()
            foreach ($p in $json.products) {
                $name = $p.name.ToLower()
                if ($json.category -eq "camera" -and $name -match "camera") {
                    if ($name -match "bag|case|cleaning|accessory|kit|tripod|memory|charger|cable") {
                        $camAcc += $p.name
                    }
                }
            }
            if ($camAcc.Count -gt 0) { Write-Output "CAMERA ACCESSORY ISSUE: $($camAcc.Count) products: $($camAcc -join ', ')" }
            # Show first 3
            $first3 = $json.products | Select-Object -First 3
            foreach ($p in $first3) {
                Write-Output "  ACCEPTED: $($p.name) [$($p.retailers -join ', ')] Price: $($p.lowestPrice)"
            }
        }
    } catch {
        Write-Output "TIMEOUT/ERROR: $($_.Exception.Message)"
    }
    Write-Output ""
}

Test-Search -Query "mobiles"
Test-Search -Query "Samsung mobiles"
Test-Search -Query "tablets"
Test-Search -Query "camera"
Test-Search -Query "Samsung TV"
Test-Search -Query "laptops"
Test-Search -Query "AC"
Test-Search -Query "refrigerator"
Test-Search -Query "washing machine"
Test-Search -Query "TV"
