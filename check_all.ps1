$c = Get-Content "src/main/resources/static/app.js" -Raw

# Check all key functions
$funcs = @(
    "function showSection",
    "function hideSection",
    "function runSearch",
    "function renderCategoryListing",
    "function runCategorySearch",
    "function renderCategoryCard",
    "function renderCardSpecs",
    "function categoryUrl",
    "function clearAllFilters",
    "function reloadCategoryListing",
    "function showProductDetail",
    "function loadProductDetail",
    "function renderSmartSearch",
    "function loadComparison"
)

foreach ($f in $funcs) {
    $idx = $c.IndexOf($f)
    Write-Host "$f : $idx"
}

# Check for orphaned code patterns
$orphan1 = $c.IndexOf("showSection(els.categorySection)")
Write-Host "showSection(els.categorySection) at: $orphan1"
