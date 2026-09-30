$c = Get-Content "src/main/resources/static/app.js" -Raw
$idx = $c.IndexOf("function showSection")
Write-Host "showSection at index: $idx"
if ($idx -ge 0) {
    Write-Host "Context:" $c.Substring([Math]::Max(0,$idx-50), 100)
}
$idx2 = $c.IndexOf("categoryCurrentQuery = query")
Write-Host "categoryCurrentQuery at index: $idx2"
if ($idx2 -ge 0) {
    Write-Host "Context:" $c.Substring([Math]::Max(0,$idx2-50), 100)
}
$idx3 = $c.IndexOf("function runSearch")
Write-Host "runSearch at index: $idx3"
$idx4 = $c.IndexOf("function renderCategoryListing")
Write-Host "renderCategoryListing at index: $idx4"
$idx5 = $c.IndexOf("function renderCategoryCard")
Write-Host "renderCategoryCard at index: $idx5"
$idx6 = $c.IndexOf("function renderCardSpecs")
Write-Host "renderCardSpecs at index: $idx6"
$idx7 = $c.IndexOf("function categoryUrl")
Write-Host "categoryUrl at index: $idx7"
$idx8 = $c.IndexOf("function clearAllFilters")
Write-Host "clearAllFilters at index: $idx8"
$idx9 = $c.IndexOf("function reloadCategoryListing")
Write-Host "reloadCategoryListing at index: $idx9"
$idx10 = $c.IndexOf("function runCategorySearch")
Write-Host "runCategorySearch at index: $idx10"
