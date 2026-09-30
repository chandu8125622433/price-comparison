/** * PriceComparison frontend MVP. * Talks to the existing Spring Boot API (same origin): *   GET  /api/products/search?q=... *   GET  /api/products/{id}/compare *   POST /api/products/{id}/refresh-prices *   GET  /api/products/{id}/discover            (Phase 3, read-only) *   POST /api/products/{id}/retailer-links      (Phase 4, confirm candidate) */(function () {
    "use strict";
    var APP_VERSION = "v7";
    var els = {
        searchSection: document.getElementById("search-section"),
        searchForm: document.getElementById("search-form"),
        searchInput: document.getElementById("search-input"),
        searchButton: document.getElementById("search-button"),
        searchStatus: document.getElementById("search-status"),
        searchResults: document.getElementById("search-results"),
        searchAutocomplete: document.getElementById("search-autocomplete"),
        categorySection: document.getElementById("category-section"),
        catBackButton: document.getElementById("cat-back-button"),
        categoryHeader: document.getElementById("category-header"),
        categoryFilters: document.getElementById("category-filters"),
        categoryStatus: document.getElementById("category-status"),
        categoryResults: document.getElementById("category-results"),
        categoryPagination: document.getElementById("category-pagination"),
        compareSection: document.getElementById("compare-section"),
        backButton: document.getElementById("back-button"),
        brandLink: document.getElementById("brand-link"),
        productHeader: document.getElementById("product-header"),
        refreshBtn: document.getElementById("refresh-btn"),
        refreshStatus: document.getElementById("refresh-status"),
        refreshSummary: document.getElementById("refresh-summary"),
        comparisonStatus: document.getElementById("comparison-status"),
        priceList: document.getElementById("price-list"),
        discoverBtn: document.getElementById("discover-btn"),
        discoverSection: document.getElementById("discover-section"),
        discoverStatus: document.getElementById("discover-status"),
        candidateList: document.getElementById("candidate-list"),
        multiRetailerSection: document.getElementById("multi-retailer-section"),
        multiRetailerStatus: document.getElementById("multi-retailer-status"),
        multiRetailerResults: document.getElementById("multi-retailer-results"),
        compareRetailersBtn: document.getElementById("compare-retailers-btn"),
        productDetailSection: document.getElementById("product-detail-section"),
        detailBackButton: document.getElementById("detail-back-button"),
        detailImage: document.getElementById("detail-image"),
        detailHeader: document.getElementById("detail-header"),
        detailMeta: document.getElementById("detail-meta"),
        detailPricing: document.getElementById("detail-pricing"),
        detailActions: document.getElementById("detail-actions"),
        detailPricesStatus: document.getElementById("detail-prices-status"),
        detailPriceList: document.getElementById("detail-price-list")
    }

    var currentProductId = null;
    var lastDetailProduct = null;
    var categoryCurrentQuery = "";
    var categoryCardIndex = 0;
    var categoryPage = 1;
    var categoryPageSize = 100;
    var categoryTotalProducts = 0;
    var categoryBrands = [];
    var categoryMinPrice = "";
    var categoryMaxPrice = "";
    var categorySort = "price-asc";
    /* ---------- small helpers ---------- */
    var HTML_ESCAPES = { "&": "amp", "<": "lt", ">": "gt", '"': "quot", "'": "#39" }
    /* The five required live retailers. Demo/seed retailers (DemoStore,       DemoElectronics, SampleMart, TestStore, ...) are never shown in the       user-facing comparison ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â they remain in the database as seed data only. */
    ;;var REQUIRED_LIVE_RETAILERS = [
        "vijay sales", "reliance digital", "flipkart", "amazon", "amazon india", "croma"
    ];
    function isLiveRetailer(name) {
        return REQUIRED_LIVE_RETAILERS.indexOf(String(name || "").trim().toLowerCase()) !== -1;
    }
    /* ---------- category search classification ---------- */
    var CATEGORY_ALIASES = {
        "mobiles": true, "mobile": true, "smartphones": true, "smartphone": true,
        "phones": true, "phone": true, "cell phones": true, "cell phone": true,
        "laptops": true, "laptop": true, "notebooks": true, "notebook": true,
        "tvs": true, "tv": true, "televisions": true, "television": true,
        "air conditioners": true, "air conditioner": true, "ac": true,
        "refrigerators": true, "refrigerator": true, "fridge": true, "fridges": true,
        "washing machines": true, "washing machine": true,
        "headphones": true, "headphone": true, "earbuds": true, "earbud": true,
        "earphones": true, "earphone": true
    }

    function isCategoryQuery(query) {
        if (!query) return false;
        var normalized = query.trim().toLowerCase();
        if (CATEGORY_ALIASES[normalized]) return true;
        // "{brand} {category}" pattern.
        var parts = normalized.split(/\s+/);
        if (parts.length >= 2) {
            var last = parts[parts.length - 1];
            var lastTwo = parts.slice(-2).join(" ");
            if (CATEGORY_ALIASES[last] || CATEGORY_ALIASES[lastTwo]) return true;
        }
        return false;
    }
    function escapeHtml(value) {
        return String(value == null ? "" : value).replace(/[&<>"']/g, function (ch) {
            return "&" + HTML_ESCAPES[ch] + ";";
        });
    }
    function formatPrice(value) {
        var n = Number(value);
        if (isNaN(n)) return "-";
        return new Intl.NumberFormat("en-IN", {
            style: "currency",
            currency: "INR",
            maximumFractionDigits: 2
        }).format(n);
    }
    function formatUpdatedAt(iso) {
        if (!iso) return "unknown";
        var d = new Date(iso);
        return isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
    }
    function setStatus(el, message, cssClass) {
        el.textContent = message;
        el.className = "status-area " + cssClass;
        el.hidden = false;
    }
    function clearStatus(el) {
        el.textContent = "";
        el.hidden = true;
    }
    /* ---------- API helpers ---------- */
    function readErrorBody(res) {
        return res.json().catch(function () { return {}; });
    }
    function apiGet(url) {
        return fetch(url).then(function (res) {
            if (!res.ok) {
                return readErrorBody(res).then(function (body) {
                    throw new Error(res.status === 404
                        ? (body.message || "Not found")
                        : ("Request failed (HTTP " + res.status + ")"));
                });
            }
            return res.json();
        });
    }
    function apiPost(url) {
        return fetch(url, { method: "POST" }).then(function (res) {
            if (!res.ok) {
                return readErrorBody(res).then(function (body) {
                    throw new Error(body.message || ("Request failed (HTTP " + res.status + ")"));
                });
            }
            return res.json();
        });
    }
    function apiPostJson(url, body) {
        return fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body)
        }).then(function (res) {
            if (!res.ok) {
                return readErrorBody(res).then(function (errBody) {
                    throw new Error(errBody.message ||
                        ("Request failed (HTTP " + res.status + ")"));
                });
            }
            return res.json();
        });
    }
    /* ---------- view switching ---------- */
    function showSearch() {
        els.compareSection.hidden = true;
        els.categorySection.hidden = true;
        els.productDetailSection.hidden = true;
        els.searchSection.hidden = false;
        lastDetailProduct = null;
        categoryCurrentQuery = "";
        categoryPage = 1;
        categoryTotalProducts = 0;
        categoryBrands = [];
        categoryMinPrice = "";
        categoryMaxPrice = "";
        categorySort = "price-asc";
        selectedFilters = { brand: [] }
        categorySpecFilters = {}
        window.scrollTo({ top: 0 });
    }
    function openComparison(productId) {
        currentProductId = productId;
        els.searchSection.hidden = true;
        els.categorySection.hidden = true;
        els.compareSection.hidden = false;
        els.productDetailSection.hidden = true;
        els.refreshSummary.hidden = true;
        resetDiscover();
        setRefreshLoading(false);
        loadComparison(productId);
        window.scrollTo({ top: 0 });
    }
    function showProductDetail() {
        els.searchSection.hidden = true;
        els.categorySection.hidden = true;
        els.compareSection.hidden = true;
        els.productDetailSection.hidden = false;
        window.scrollTo({ top: 0 });
    }
    function showRetailerProductDetail(product) {
        els.searchSection.hidden = true;
        els.categorySection.hidden = true;
        els.compareSection.hidden = true;
        els.productDetailSection.hidden = false;
        window.scrollTo({ top: 0 });
                // Render retailer product detail immediately from category card data
        renderRetailerProductDetail(product);
    }
    // Make globally accessible for inline onclick
    window.openComparisonFromCategory = function (productName) {
        els.searchInput.value = productName;
        runSearch(productName);
        window.scrollTo({ top: 0 });
    }
    function renderRetailerProductDetail(product) {
        els.detailHeader.innerHTML = "";
        els.detailMeta.innerHTML = "";
        els.detailPricing.innerHTML = "";
        els.detailActions.innerHTML = "";
        els.detailImage.innerHTML = "";
        els.detailPriceList.innerHTML = "";
        clearStatus(els.detailPricesStatus);
        // Image
        if (product.imageUrl) {
            els.detailImage.innerHTML = '<img src="' + escapeHtml(product.imageUrl) + '" alt="' + escapeHtml(product.name) + '" />';
        }
        // Header
        els.detailHeader.innerHTML = "<h2>" + escapeHtml(product.name) + "</h2>" +
            '<p class="product-sub">' + escapeHtml(product.brand || "Unknown Brand") + "</p>";
        // Meta
        var metaRows = [
            { label: "Brand", value: product.brand || "Not available" },
            { label: "Availability", value: product.availability || "Check retailers" },
            { label: "Retailers", value: (product.retailers && product.retailers.length) ? product.retailers.join(", ") : "Not available" }
        ];
        var metaHtml = '<dl class="detail-meta-list">';
        metaRows.forEach(function (row) {
            metaHtml += '<dt>' + escapeHtml(row.label) + '</dt>' +
                        '<dd>' + escapeHtml(row.value) + '</dd>';
        });
        metaHtml += "</dl>";
        els.detailMeta.innerHTML = metaHtml;
        // Pricing
        if (product.lowestPrice) {
            els.detailPricing.innerHTML =
                '<div class="detail-price-block">' +
                '<span class="detail-price-label">Price:</span> ' +
                '<span class="detail-price-amount">' + escapeHtml(product.lowestPrice) + '</span>' +
                '</div>';
        } else {
            els.detailPricing.innerHTML = '<p class="detail-price-na">Price unavailable</p>';
        }
        // Actions - Buy button if productUrl exists, Compare Prices button
        var actionsHtml = "";
        if (product.productUrl) {
            actionsHtml += '<a class="btn btn-primary" href="' + escapeHtml(product.productUrl) + '" target="_blank" rel="noopener noreferrer">Buy on ' + escapeHtml((product.retailers && product.retailers[0]) || "retailer") + '</a>';
        }
        actionsHtml += '<button type="button" class="btn btn-secondary" onclick="openComparisonFromCategory(\'' + escapeHtml(product.name).replace(/'/g, "\\'") + '\')">Compare Prices</button>';
        els.detailActions.innerHTML = actionsHtml;
        // Retailer prices section - show retailer info
        if (product.retailers && product.retailers.length) {
            var retailerHtml = '<h3>Available at</h3><ul style="list-style: none; padding: 0;">';
            product.retailers.forEach(function (retailer) {
                retailerHtml += '<li style="margin: 8px 0;">' + escapeHtml(retailer) + '</li>';
            });
            retailerHtml += '</ul>';
            els.detailPriceList.innerHTML = retailerHtml;
        }
    }
    function loadProductDetail(productId) {
        els.detailHeader.innerHTML = "";
        els.detailMeta.innerHTML = "";
        els.detailPricing.innerHTML = "";
        els.detailActions.innerHTML = "";
        els.detailImage.innerHTML = "";
        els.detailPriceList.innerHTML = "";
        clearStatus(els.detailPricesStatus);
        setStatus(els.detailPricesStatus, "Loading product details...", "status-info");
        apiGet("/api/products/" + productId)
            .then(function (data) {
                renderProductDetail(data);
                if (!data.prices || !data.prices.length) {
                    setStatus(els.detailPricesStatus,
                        "No retailer prices recorded yet for this product.",
                        "status-empty");
                } else {
                    clearStatus(els.detailPricesStatus);
                }
                renderDetailPrices(data.prices || []);
            })
            .catch(function (err) {
                setStatus(els.detailPricesStatus,
                    "Could not load product details: " + err.message, "status-error");
            });
    }
    function renderProductDetail(data) {
        if (lastDetailProduct && lastDetailProduct.imageUrl) {
            els.detailImage.innerHTML = '<img src="' + escapeHtml(lastDetailProduct.imageUrl) + '" alt="' + escapeHtml(data.name || "") + '" />';
        } else {
            els.detailImage.innerHTML = "";
        }
        els.detailHeader.innerHTML = "<h2>" + escapeHtml(data.name || "Product #" + data.id) + "</h2>" +
            '<p class="product-sub">' + escapeHtml(
                [data.brand, data.model, data.category].filter(Boolean).join(" | ")
            ) + "</p>";
        var metaRows = [
            { label: "Product ID", value: data.id ? String(data.id) : "Not available" },
            { label: "Brand", value: data.brand || "Not available" },
            { label: "Model", value: data.model || "Not available" },
            { label: "Category", value: data.category || "Not available" }
        ];
        var metaHtml = '<dl class="detail-meta-list">';
        metaRows.forEach(function (row) {
            metaHtml += '<dt>' + escapeHtml(row.label) + '</dt>' +
                        '<dd>' + escapeHtml(row.value) + '</dd>';
        });
        metaHtml += "</dl>";
        els.detailMeta.innerHTML = metaHtml;
        var prices = data.prices || [];
        if (prices.length > 0) {
            var best = prices[0];
            els.detailPricing.innerHTML =
                '<div class="detail-price-block">' +
                '<span class="detail-price-label">Lowest price:</span> ' +
                '<span class="detail-price-amount">' + escapeHtml(best.retailer || "") + '</span>' +
                '</div>';
        } else {
            els.detailPricing.innerHTML = '<p class="detail-price-na">Price unavailable</p>';
        }
        var buyUrl = null;
        if (prices.length > 0 && prices[0].productUrl) {
            buyUrl = prices[0].productUrl;
        }
        if (buyUrl) {
            els.detailActions.innerHTML =
                '<a class="btn btn-primary" href="' + escapeHtml(buyUrl) + '" target="_blank" rel="noopener noreferrer">Buy on ' + escapeHtml(prices[0].retailer || "retailer") + "</a>";
        } else {
            els.detailActions.innerHTML = "";
        }
    }
    function renderDetailPrices(prices) {
        els.detailPriceList.innerHTML = "";
        if (!prices || !prices.length) return;
        prices.forEach(function (price, index) {
            var isCheapest = index === 0;
            var row = document.createElement("div");
            row.className = "price-row" + (isCheapest ? " price-row-cheapest" : "");
            var rank = document.createElement("div");
            rank.className = "price-rank";
            rank.textContent = String(index + 1);
            var main = document.createElement("div");
            main.className = "price-main";
            var retailer = document.createElement("span");
            retailer.className = "retailer-name";
            retailer.textContent = price.retailer || "Unknown retailer";
            if (isCheapest) {
                var badge = document.createElement("span");
                badge.className = "badge-lowest";
                badge.textContent = "LOWEST PRICE";
                retailer.appendChild(badge);
            }
            var amount = document.createElement("div");
            amount.className = "price-amount";
            amount.textContent = formatPrice(price.price);
            var updated = document.createElement("span");
            updated.className = "price-updated";
            updated.textContent = "Last updated: " + formatUpdatedAt(price.lastUpdated);
            var actions = document.createElement("div");
            actions.className = "price-actions";
            if (price.productUrl) {
                var buy = document.createElement("a");
                buy.className = "btn btn-primary";
                buy.textContent = "Buy on " + (price.retailer || "retailer");
                buy.href = price.productUrl;
                buy.target = "_blank";
                buy.rel = "noopener noreferrer";
                actions.appendChild(buy);
            }
            main.appendChild(retailer);
            main.appendChild(amount);
            row.appendChild(rank);
            row.appendChild(main);
            row.appendChild(actions);
            row.appendChild(updated);
            els.detailPriceList.appendChild(row);
        });
    }
    /* ---------- search ---------- */
    function renderResults(products) {
        els.searchResults.innerHTML = "";
        products.forEach(function (product) {
            var card = document.createElement("article");
            card.className = "product-card";
            // Image.
            if (product.imageUrl) {
                var imgWrap = document.createElement("div");
                imgWrap.className = "card-image";
                var img = document.createElement("img");
                img.src = product.imageUrl;
                img.alt = product.name || "";
                img.loading = resultsCardIndex < 2 ? "eager" : "lazy";
                if (resultsCardIndex < 2) { img.fetchpriority = "high"; }
                resultsCardIndex++;
                img.onerror = function () {
                    img.style.display = "none";
                    var ph = document.createElement("div");
                    ph.className = "card-image-placeholder";
                    ph.textContent = "Image unavailable";
                    imgWrap.appendChild(ph);
                }
                imgWrap.appendChild(img);
                card.appendChild(imgWrap);
            }
            var info = document.createElement("div");
            info.className = "card-info";
            var name = document.createElement("h3");
            name.className = "card-name";
            name.textContent = product.name || "Product";
            info.appendChild(name);
            // Brand/model/category meta.
            var meta = document.createElement("p");
            meta.className = "product-meta";
            meta.textContent = [product.brand, product.model, product.category]
                .filter(Boolean).join(" | ");
            info.appendChild(meta);
            // Specs (from prices if available via retailer info).
            if (product.prices && product.prices.length) {
                var specDiv = document.createElement("div");
                specDiv.className = "card-specs";
                var specs = [];
                // Show retailer names as a lightweight info.
                var retailers = [];
                product.prices.forEach(function (p) {
                    if (p.retailer && retailers.indexOf(p.retailer) === -1) {
                        retailers.push(p.retailer);
                    }
                });
                if (retailers.length) {
                    specDiv.innerHTML = '<span class="card-spec-retailers">' +
                        escapeHtml(retailers.slice(0, 3).join(", ")) +
                        (retailers.length > 3 ? "..." : "") + '</span>';
                }
                info.appendChild(specDiv);
            }
            // Price.
            var priceBlock = document.createElement("div");
            priceBlock.className = "card-price";
            if (product.prices && product.prices.length) {
                var lowest = product.prices[0];
                var priceSpan = document.createElement("span");
                priceSpan.className = "card-price-amount";
                priceSpan.textContent = formatPrice(lowest.price);
                priceBlock.appendChild(priceSpan);
                if (lowest.retailer) {
                    var retailerSpan = document.createElement("span");
                    retailerSpan.className = "card-price-retailer";
                    retailerSpan.textContent = " - " + lowest.retailer;
                    priceBlock.appendChild(retailerSpan);
                }
                if (lowest.lastUpdated) {
                    var updatedSpan = document.createElement("span");
                    updatedSpan.className = "card-price-updated";
                    updatedSpan.textContent = " (as of " + formatUpdatedAt(lowest.lastUpdated) + ")";
                    priceBlock.appendChild(updatedSpan);
                }
                if (lowest.fresh === false) {
                    var staleSpan = document.createElement("span");
                    staleSpan.className = "card-price-stale";
                    staleSpan.textContent = " - Stale / refreshing";
                    priceBlock.appendChild(staleSpan);
                }
                if (product.prices.length > 1) {
                    var count = document.createElement("span");
                    count.className = "card-price-count";
                    count.textContent = product.prices.length + " retailer" +
                        (product.prices.length > 1 ? "s" : "");
                    priceBlock.appendChild(count);
                }
            } else {
                var na = document.createElement("span");
                na.className = "card-price-unavailable";
                na.textContent = "Price unavailable";
                priceBlock.appendChild(na);
            }
            info.appendChild(priceBlock);
            // Compare button.
            var openBtn = document.createElement("button");
            openBtn.type = "button";
            openBtn.className = "btn btn-primary card-compare";
            openBtn.textContent = "Compare prices";
            openBtn.dataset.productId = product.id;
            info.appendChild(openBtn);
            // View Details button.
            var detailBtn = document.createElement("button");
            detailBtn.type = "button";
            detailBtn.className = "btn btn-secondary card-detail";
            detailBtn.textContent = "View Details";
            detailBtn.addEventListener("click", function () {
                if (product.id) {
                    lastDetailProduct = product;
                    showProductDetail();
                    loadProductDetail(product.id);
                }
            });
            info.appendChild(detailBtn);
            card.appendChild(info);
            els.searchResults.appendChild(card);
        });
    }
    /* ---------- category product-listing search ---------- */
    function runSearch(query) {
        clearStatus(els.searchStatus);
        els.searchResults.innerHTML = "";
        hideSection(els.categorySection);
        els.searchButton.disabled = true;
        hideAutocomplete();
        setStatus(els.searchStatus, 'Searching for "' + query + '"...', "status-info");
        // Single entry point: the backend classifies the query. A category
        // alias ("mobiles") returns a CATEGORY_RESULT listing; everything else
        // returns a specific-product smart-search response (fresh cache, stale
        // cache or DB-miss discovery). "Samsung TV" (brand + category) is
        // treated by the backend as a specific search when a matching product
        // exists in the database.
        apiGet("/api/products/smart-search?q=" + encodeURIComponent(query) + "&page=1&pageSize=100")
            .then(function (resp) {
                if (resp && resp.decision === "CATEGORY_RESULT" && resp.categoryListing) {
                    categoryCurrentQuery = query;
                    categoryPage = 1;
                    categoryBrands = [];
                    categoryMinPrice = "";
                    categoryMaxPrice = "";
                    categorySort = "price-asc";
                    renderCategoryListing(resp.categoryListing);
                } else if (resp && resp.decision === "CATEGORY_RESULT" && resp.products && resp.products.length) {
                    renderCategoryListing({
                        query: resp.query,
                        category: "",
                        categoryDisplayName: resp.message || "Products",
                        description: "",
                        totalProducts: resp.products.length,
                        products: resp.products,
                        filters: []
                    });
        } else {
            renderSmartSearch(resp);
        }
    })
    .catch(function (err) {
        setStatus(els.searchStatus, "Search failed: " + err.message, "status-error");
    })
    .finally(function () {
        els.searchButton.disabled = false;
    });}
    function showSection(el) { if (el) el.hidden = false; }
    function hideSection(el) { if (el) el.hidden = true; }
    function renderCategoryListing(resp) {
        clearStatus(els.categoryStatus);
        clearStatus(els.searchStatus);
        els.categoryResults.innerHTML = "";
        els.categoryPagination.innerHTML = "";
        hideSection(els.compareSection);
        hideSection(els.searchSection);
        showSection(els.categorySection);
        // Re-render chips if filters exist (for Load More navigation).
        var existingChips = document.getElementById("filter-chips");
        if (existingChips) {
            renderFilterChips(existingChips);
        }
        if (!resp || !resp.products || !resp.products.length) {
            setStatus(els.categoryStatus,
                (resp && resp.message) || "No products found for these filters. Try adjusting or clearing filters.",
                "status-empty");
            els.categoryResults.innerHTML = '<div class="empty-state"><p>No products match these filters.</p>'
                + '<button type="button" class="btn btn-primary" onclick="clearAllFiltersFromUI()">Clear Filters</button></div>';
            els.categoryFilters.querySelectorAll(".filter-body").forEach(function (body) { body.style.display = "block"; });
            categoryTotalProducts = 0;
            categoryPage = 1;
            return;
        }
        categoryTotalProducts = resp.totalProducts;
        categoryPage = 1;
        // Header.
        var title = (resp.categoryDisplayName && resp.categoryDisplayName.replace(/s$/, "'s"))
                || (resp.category + " products") || "Products";
        els.categoryHeader.innerHTML = "<h2>" + escapeHtml(title) + "</h2>"
            + (resp.description ? '<p class="category-desc">' + escapeHtml(resp.description) + "</p>" : "")
            + '<p class="category-count">' + escapeHtml(String(resp.totalProducts)) + " products found</p>";
        // Filters.
        renderCategoryFilters(resp.filters || []);
        // Product cards (first page).
        resp.products.forEach(function (product) {
            els.categoryResults.appendChild(renderCategoryCard(product));
        });
        // Load More button.
        if (categoryTotalProducts > categoryPageSize) {
            var loadMore = document.createElement("button");
            loadMore.type = "button";
            loadMore.className = "btn btn-secondary load-more";
            loadMore.textContent = "Load More (" + (categoryTotalProducts - categoryPageSize) + " remaining)";
            loadMore.addEventListener("click", function () {
                loadMoreCategoryPage();
            });
            els.categoryPagination.appendChild(loadMore);
        }
    }
    function runCategorySearch(query) {
        categoryCurrentQuery = query;
        categoryPage = 1;
        categoryBrands = [];
        categoryMinPrice = "";
        categoryMaxPrice = "";
        categorySort = "price-asc";
        selectedFilters = { brand: [] }
        categorySpecFilters = {}
        showSection(els.categorySection);
        hideSection(els.compareSection);
        els.searchResults.innerHTML = "";
        clearStatus(els.searchStatus);
        setStatus(els.categoryStatus, 'Searching for ' + query + '...', "status-info");
        apiGet(categoryUrl(1))
            .then(function (resp) {
                renderCategoryListing(resp);
            })
            .catch(function (err) {
                setStatus(els.categoryStatus, "Search failed: " + err.message, "status-error");
            })
            .finally(function () {
                els.searchButton.disabled = false;
            });
    }
    function clearAllFiltersFromUI() {
        clearAllFilters();
        reloadCategoryListing();
    }
    function loadMoreCategoryPage() {
        var nextPage = categoryPage + 1;
        var btn = els.categoryPagination.querySelector(".load-more");
        if (btn) {
            btn.disabled = true;
            btn.textContent = "Loading...";
        }
        apiGet(categoryUrl(nextPage))
            .then(function (resp) {
                if (resp && resp.products && resp.products.length) {
                    categoryPage = nextPage;
                    resp.products.forEach(function (product) {
                        els.categoryResults.appendChild(renderCategoryCard(product));
                    });
                    categoryTotalProducts = resp.totalProducts;
                    if (btn) {
                        var remaining = categoryTotalProducts - categoryPage * categoryPageSize;
                        if (remaining > 0) {
                            btn.disabled = false;
                            btn.textContent = "Load More (" + remaining + " remaining)";
                        } else {
                            els.categoryPagination.innerHTML = "";
                        }
                    }
                } else {
                    if (btn) {
                        els.categoryPagination.innerHTML = "";
                    }
                }
            })
            .catch(function (err) {
                if (btn) {
                    btn.disabled = false;
                    btn.textContent = "Load More";
                }
                setStatus(els.categoryStatus, "Failed to load more: " + err.message, "status-error");
            });
    }
    var selectedFilters = { brand: [] }
    var categorySpecFilters = {}
    function renderCategoryFilters(filters) {
        els.categoryFilters.innerHTML = "";
        // Applied filter chips.
        var chipsContainer = document.createElement("div");
        chipsContainer.className = "filter-chips";
        chipsContainer.id = "filter-chips";
        renderFilterChips(chipsContainer);
        els.categoryFilters.appendChild(chipsContainer);
        // Sort + price controls.
        var controls = document.createElement("div");
        controls.className = "filter-group filter-collapsible";
        controls.innerHTML = '<h4 class="filter-group-label">'
            + '<span class="filter-toggle">[open]</span> Sort &amp; Price</h4>';
        var toggle = controls.querySelector(".filter-toggle");
        var controlsBody = document.createElement("div");
        controlsBody.className = "filter-body";
        controls.appendChild(controlsBody);
        var sort = document.createElement("select");
        sort.className = "filter-select";
        [["price-asc", "Price: Low to High"], ["price-desc", "Price: High to Low"], ["name-asc", "Name: A to Z"]]
            .forEach(function (option) {
                var el = document.createElement("option");
                el.value = option[0]; el.textContent = option[1];
                el.selected = categorySort === option[0];
                sort.appendChild(el);
            });
        sort.addEventListener("change", function () { categorySort = sort.value; reloadCategoryListing(); });
        controlsBody.appendChild(sort);
        var priceWrap = document.createElement("div");
        priceWrap.className = "filter-price-row";
        var min = document.createElement("input");
        min.type = "number"; min.min = "0"; min.placeholder = "Min price"; min.value = categoryMinPrice;
        min.className = "filter-input";
        min.addEventListener("change", function () { categoryMinPrice = min.value; reloadCategoryListing(); });
        var max = document.createElement("input");
        max.type = "number"; max.min = "0"; max.placeholder = "Max price"; max.value = categoryMaxPrice;
        max.className = "filter-input";
        max.addEventListener("change", function () { categoryMaxPrice = max.value; reloadCategoryListing(); });
        priceWrap.appendChild(min);
        priceWrap.appendChild(max);
        controlsBody.appendChild(priceWrap);
        toggle.addEventListener("click", function () {
            var isOpen = controlsBody.style.display !== "none";
            controlsBody.style.display = isOpen ? "none" : "block";
            toggle.textContent = "[" + (isOpen ? "close" : "open") + "]";
        });
        els.categoryFilters.appendChild(controls);
        if (!filters || !filters.length) return;
        filters.forEach(function (group) {
            var section = createFilterGroup(group);
            els.categoryFilters.appendChild(section);
        });
    }
    function createFilterGroup(group) {
        var section = document.createElement("div");
        section.className = "filter-group filter-collapsible";
        var label = document.createElement("h4");
        label.className = "filter-group-label";
        label.innerHTML = '<span class="filter-toggle">[open]</span> ' + escapeHtml(group.label);
        var toggle = label.querySelector(".filter-toggle");
        var body = document.createElement("div");
        body.className = "filter-body";
        section.appendChild(label);
        section.appendChild(body);
        toggle.addEventListener("click", function () {
            var isOpen = body.style.display !== "none";
            body.style.display = isOpen ? "none" : "block";
            toggle.textContent = "[" + (isOpen ? "close" : "open") + "]";
        });
        if (!group.enabled || !group.options || !group.options.length) {
            var empty = document.createElement("p");
            empty.className = "filter-empty";
            empty.textContent = "No filters available";
            body.appendChild(empty);
        } else {
            var longList = group.options.length > 8;
            var visibleCount = longList ? 8 : group.options.length;
            var searchBox = null;
            if (longList) {
                searchBox = document.createElement("input");
                searchBox.type = "text";
                searchBox.className = "filter-search";
                searchBox.placeholder = "Search " + escapeHtml(group.label.toLowerCase());
                body.appendChild(searchBox);
            }
            var listContainer = document.createElement("div");
            listContainer.className = "filter-options-list";
            body.appendChild(listContainer);
            function renderList(options) {
                listContainer.innerHTML = "";
                options.forEach(function (opt) {
                    var row = document.createElement("label");
                    row.className = "filter-option";
                    var cb = document.createElement("input");
                    cb.type = "checkbox";
                    cb.value = opt.value;
                    var isChecked = false;
                    if (group.key === "brand") {
                        isChecked = categoryBrands.indexOf(opt.value) !== -1;
                    } else {
                        var specValues = categorySpecFilters[group.key] || [];
                        isChecked = specValues.indexOf(opt.value) !== -1;
                    }
                    cb.checked = isChecked;
                    cb.addEventListener("change", function () {
                        if (group.key === "brand") {
                            if (cb.checked) {
                                if (categoryBrands.indexOf(opt.value) === -1) {
                                    categoryBrands.push(opt.value);
                                }
                            } else {
                                categoryBrands = categoryBrands.filter(function (b) { return b !== opt.value; });
                            }
                        } else {
                            if (!categorySpecFilters[group.key]) {
                                categorySpecFilters[group.key] = [];
                            }
                            if (cb.checked) {
                                if (categorySpecFilters[group.key].indexOf(opt.value) === -1) {
                                    categorySpecFilters[group.key].push(opt.value);
                                }
                            } else {
                                categorySpecFilters[group.key] = categorySpecFilters[group.key].filter(function (v) { return v !== opt.value; });
                                if (categorySpecFilters[group.key].length === 0) {
                                    delete categorySpecFilters[group.key];
                                }
                            }
                        }
                        reloadCategoryListing();
                    });
                    var span = document.createElement("span");
                    span.textContent = opt.label + " (" + opt.count + ")";
                    row.appendChild(cb);
                    row.appendChild(span);
                    listContainer.appendChild(row);
                });
            }
            renderList(group.options.slice(0, visibleCount));
            if (longList) {
                var showMore = document.createElement("button");
                showMore.type = "button";
                showMore.className = "filter-show-more";
                showMore.textContent = "Show more (" + (group.options.length - visibleCount) + ")";
                showMore.addEventListener("click", function () {
                    renderList(group.options);
                    showMore.style.display = "none";
                });
                body.appendChild(showMore);
                searchBox.addEventListener("input", function () {
                    var query = searchBox.value.trim().toLowerCase();
                    var filtered = query
                        ? group.options.filter(function (o) { return o.label.toLowerCase().indexOf(query) !== -1; })
                        : group.options;
                    var shown = query ? filtered.slice(0, 20) : filtered.slice(0, visibleCount);
                    renderList(shown);
                    if (!query && showMore) showMore.style.display = "inline-block";
                });
            }
        }
        return section;
    }
    function renderFilterChips(container) {
        container.innerHTML = "";
        var hasFilters = categoryBrands.length > 0 || Object.keys(categorySpecFilters).length > 0 || categoryMinPrice || categoryMaxPrice;
        if (!hasFilters) return;
        var chipsWrapper = document.createElement("div");
        chipsWrapper.className = "filter-chips-row";
        categoryBrands.forEach(function (brand) {
            chipsWrapper.appendChild(createChip(brand, "brand", brand));
        });
        Object.keys(categorySpecFilters).forEach(function (key) {
            categorySpecFilters[key].forEach(function (value) {
                renderFilterChip(chipsWrapper, key, value);
            });
        });
        if (categoryMinPrice) {
            chipsWrapper.appendChild(createChip("Min: " + formatPrice(categoryMinPrice), "minPrice", categoryMinPrice));
        }
        if (categoryMaxPrice) {
            chipsWrapper.appendChild(createChip("Max: " + formatPrice(categoryMaxPrice), "maxPrice", categoryMaxPrice));
        }
        var clearAll = document.createElement("button");
        clearAll.type = "button";
        clearAll.className = "filter-chip filter-chip-clear";
        clearAll.textContent = "Clear All";
        clearAll.addEventListener("click", function () { clearAllFilters(); });
        chipsWrapper.appendChild(clearAll);
        container.appendChild(chipsWrapper);
    }
    function renderFilterChip(container, groupKey, value) {
        var label = value;
        var specLabels = { ram: "RAM", storage: "Storage", processor: "Processor", screenSize: "Screen Size", resolution: "Resolution", displayType: "Display Type", refreshRate: "Refresh Rate", tonnage: "Tonnage", starRating: "Star Rating", acType: "AC Type", capacity: "Capacity", doorType: "Door Type", loadType: "Load Type", cameraType: "Camera Type", resolutionMP: "Resolution", headphoneType: "Type", wireless: "Wireless", anc: "ANC", watchDisplay: "Display", gps: "GPS", cellular: "Cellular", fiveG: "5G" }
        if (specLabels[groupKey]) {
            label = specLabels[groupKey] + ": " + value;
        }
        container.appendChild(createChip(label, groupKey, value));
    }
    function createChip(label, filterKey, filterValue) {
        var chip = document.createElement("span");
        chip.className = "filter-chip";
        chip.innerHTML = escapeHtml(label) + " <span class=\"filter-chip-x\">&times;</span>";
        chip.querySelector(".filter-chip-x").addEventListener("click", function () {
            removeFilter(filterKey, filterValue);
        });
        return chip;
    }
    function removeFilter(key, value) {
        if (key === "brand") {
            categoryBrands = categoryBrands.filter(function (b) { return b !== value; });
        } else if (key === "minPrice") {
            categoryMinPrice = "";
        } else if (key === "maxPrice") {
            categoryMaxPrice = "";
        } else {
            if (categorySpecFilters[key]) {
                categorySpecFilters[key] = categorySpecFilters[key].filter(function (v) { return v !== value; });
                if (categorySpecFilters[key].length === 0) {
                    delete categorySpecFilters[key];
                }
            }
        }
        reloadCategoryListing();
    }
    function clearAllFilters() {
        categoryBrands = [];
        categoryMinPrice = "";
        categoryMaxPrice = "";
        categorySpecFilters = {}
        reloadCategoryListing();
    }
    function categoryUrl(page) {
        var params = new URLSearchParams({ q: categoryCurrentQuery, page: String(page), pageSize: String(categoryPageSize), sort: categorySort });
        categoryBrands.forEach(function (brand) { params.append("brand", brand); });
        if (categoryMinPrice) params.set("minPrice", categoryMinPrice);
        if (categoryMaxPrice) params.set("maxPrice", categoryMaxPrice);
        Object.keys(categorySpecFilters).forEach(function (groupKey) {
            categorySpecFilters[groupKey].forEach(function (value) {
                params.append("filters", groupKey + ":" + value);
            });
        });
        return "/api/products/category-search?" + params.toString();
    }
    function reloadCategoryListing() {
        if (!categoryCurrentQuery) return;
        setStatus(els.categoryStatus, "Updating results...", "status-info");
        apiGet(categoryUrl(1)).then(renderCategoryListing).catch(function (err) {
            setStatus(els.categoryStatus, "Search failed: " + err.message, "status-error");
        });
    }
    function renderCategoryCard(product) {
        var card = document.createElement("article");
        card.className = "category-card";
        // Image.
        if (product.imageUrl) {
            var imgWrap = document.createElement("div");
            imgWrap.className = "card-image";
            var img = document.createElement("img");
            img.src = product.imageUrl;
            img.alt = product.name || "";
            img.loading = categoryCardIndex < 2 ? "eager" : "lazy";
            if (categoryCardIndex < 2) { img.fetchpriority = "high"; }
            categoryCardIndex++;
            img.onerror = function () {
                img.style.display = "none";
                var ph = document.createElement("div");
                ph.className = "card-image-placeholder";
                ph.textContent = "Image unavailable";
                imgWrap.appendChild(ph);
            }
            imgWrap.appendChild(img);
            card.appendChild(imgWrap);
        }
        // Info.
        var info = document.createElement("div");
        info.className = "card-info";
        // Name.
        var name = document.createElement("h3");
        name.className = "card-name";
        name.textContent = product.name || "Product";
        info.appendChild(name);
        // Specifications.
        if (product.specs) {
            var specsHtml = renderCardSpecs(product.specs);
            if (specsHtml) {
                var specsDiv = document.createElement("div");
                specsDiv.className = "card-specs";
                specsDiv.innerHTML = specsHtml;
                info.appendChild(specsDiv);
            }
        }
        // Price.
        var priceBlock = document.createElement("div");
        priceBlock.className = "card-price";
        if (product.offers && product.offers.length) {
            var offersHtml = renderCardOffers(product.offers);
            var offersDiv = document.createElement("div");
            offersDiv.className = "card-offers";
            offersDiv.innerHTML = offersHtml;
            priceBlock.appendChild(offersDiv);
        } else if (product.lowestPrice) {
            var price = document.createElement("span");
            price.className = "card-price-amount";
            price.textContent = formatPrice(product.lowestPrice);
            priceBlock.appendChild(price);
        } else {
            var na = document.createElement("span");
            na.className = "card-price-unavailable";
            na.textContent = "Price unavailable";
            priceBlock.appendChild(na);
        }
        info.appendChild(priceBlock);
        // Retailer status.
        var status = document.createElement("div");
        status.className = "card-status";
        if (product.retailers && product.retailers.length) {
            status.textContent = product.retailers.join(", ");
        } else if (product.offerCount && product.offerCount > 0) {
            status.textContent = product.offerCount + " retailer"
                + (product.offerCount > 1 ? "s" : "") + " available";
        } else {
            status.textContent = "Check retailers";
        }
        info.appendChild(status);
        // Compare button.
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-primary card-compare";
        btn.textContent = "Compare prices";
        btn.addEventListener("click", function () {
            els.searchInput.value = product.name;
            runSearch(product.name);
            window.scrollTo({ top: 0 });
        });
        info.appendChild(btn);
        // View Details button.
        var detailBtn = document.createElement("button");
        detailBtn.type = "button";
        detailBtn.className = "btn btn-secondary card-detail";
        detailBtn.textContent = "View Details";
        detailBtn.addEventListener("click", function () {
            if (product.productId) {
                lastDetailProduct = product;
                showProductDetail();
                loadProductDetail(product.productId);
            } else {
                lastDetailProduct = product;
                showRetailerProductDetail(product);
            }
        });
        info.appendChild(detailBtn);
        card.appendChild(info);
        return card;
    }
    function renderCardOffers(offers) {
        if (!offers || !offers.length) return "";
        var html = "";
        offers.forEach(function (o) {
            var p = formatPrice(o.price);
            var mrp = o.mrp ? " MRP " + formatPrice(o.mrp) : "";
            var avail = o.availability ? " | " + o.availability : "";
            var url = o.productUrl || "#";
            var retailerName = escapeHtml(o.retailer) || "Retailer";
            html += "<a class=\"card-retailer-link\" href=\"" + url + "\" target=\"_blank\" rel=\"noopener noreferrer\">";
            html += retailerName + "</a>";
            html += " <span class=\"card-offer-price\">" + p + mrp + "</span>";
            if (avail) html += " <span class=\"card-offer-avail\">" + avail + "</span>";
        });
        return html;
    }
function renderCardSpecs(specs) {
        if (!specs) return "";
        var pairs = [];
        if (specs.ram) pairs.push(specs.ram + " GB RAM");
        if (specs.storage) pairs.push(specs.storage + " GB Storage");
        if (specs.processor) pairs.push(specs.processor);
        if (specs.screenSize) pairs.push(specs.screenSize + "\" Screen");
        if (specs.resolution) pairs.push(specs.resolution);
        if (specs.displayType) pairs.push(specs.displayType);
        if (specs.refreshRate) pairs.push(specs.refreshRate + "Hz");
        if (specs.tonnage) pairs.push(specs.tonnage + " Ton");
        if (specs.starRating) pairs.push(specs.starRating + " Star");
        if (specs.acType) pairs.push(specs.acType);
        if (specs.capacity) pairs.push(specs.capacity + "L");
        if (specs.doorType) pairs.push(specs.doorType);
        if (specs.loadType) pairs.push(specs.loadType);
        if (specs.cameraType) pairs.push(specs.cameraType);
        if (specs.resolutionMP) pairs.push(specs.resolutionMP + " MP");
        if (specs.headphoneType) pairs.push(specs.headphoneType);
        if (specs.wireless) pairs.push(specs.wireless ? "Wireless" : "Wired");
        if (specs.anc) pairs.push("ANC");
        if (specs.watchDisplay) pairs.push(specs.watchDisplay);
        if (specs.gps) pairs.push("GPS");
        if (specs.cellular) pairs.push("Cellular");
        if (specs.fiveG) pairs.push("5G");
        if (!pairs.length) return "";
        return pairs.map(function (p) { return "<span class=\"card-spec-item\">" + escapeHtml(p) + "</span>"; }).join("");
    }
    /* ---------- search autocomplete ---------- */
    var autocompleteTimer = null;
    function hideAutocomplete() {
        if (els.searchAutocomplete) {
            els.searchAutocomplete.innerHTML = "";
            els.searchAutocomplete.hidden = true;
        }
    }
    function setupAutocomplete() {
        if (!els.searchInput) return;
        els.searchInput.addEventListener("input", function () {
            clearTimeout(autocompleteTimer);
            var val = els.searchInput.value.trim();
            if (!val) { hideAutocomplete(); return; }
            autocompleteTimer = setTimeout(function () {
                apiGet("/api/products/search-suggestions?q=" + encodeURIComponent(val))
                    .then(function (resp) {
                        renderAutocomplete(resp, val);
                    })
                    .catch(function () { hideAutocomplete(); });
            }, 200);
        });
        document.addEventListener("click", function (e) {
            if (!els.searchAutocomplete.contains(e.target) && e.target !== els.searchInput) {
                hideAutocomplete();
            }
        });
    }
    function renderAutocomplete(resp, query) {
        if (!els.searchAutocomplete) return;
        els.searchAutocomplete.innerHTML = "";
        if (!resp || !resp.suggestions || !resp.suggestions.length) {
            hideAutocomplete();
            return;
        }
        resp.suggestions.forEach(function (s) {
            var item = document.createElement("div");
            item.className = "autocomplete-item";
            item.textContent = s.text;
            item.addEventListener("click", function () {
                els.searchInput.value = s.text;
                hideAutocomplete();
                runSearch(s.text);
            });
            els.searchAutocomplete.appendChild(item);
        });
        els.searchAutocomplete.hidden = false;
    }
    function renderSmartSearch(resp) {
        clearStatus(els.searchStatus);
        els.searchResults.innerHTML = "";
        if (!resp || !resp.products || !resp.products.length) {
            // DB miss with no strict match (or empty query) ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ controlled message,
            // never the bare "No products found".
            setStatus(els.searchStatus,
                (resp && resp.message) ||
                    'No matching product found. Try another search.',
                "status-empty");
            return;
        }
        renderResults(resp.products);
    }
    /* ---------- comparison ---------- */
    function loadComparison(productId) {
        clearStatus(els.comparisonStatus);
        els.priceList.innerHTML = "";
        setStatus(els.comparisonStatus, "Loading prices...", "status-info");
        apiGet("/api/products/" + productId + "/compare")
            .then(function (data) {
                renderComparison(data);
                if (!data.retailerPrices || !data.retailerPrices.length) {
                    setStatus(els.comparisonStatus,
                        "No retailer prices recorded yet for this product. " +
                        'Use "Refresh Prices" to fetch live prices from registered retailers.',
                        "status-empty");
                } else {
                    clearStatus(els.comparisonStatus);
                }
            })
            .catch(function (err) {
                setStatus(els.comparisonStatus,
                    "Could not load comparison: " + err.message, "status-error");
            });
    }
    function renderComparison(data) {
        currentProductId = data.productId;
        els.productHeader.innerHTML =
            "<h2>" + escapeHtml(data.name || "Product #" + data.productId) + "</h2>" +
            '<p class="product-sub">' + escapeHtml(
                [data.brand, data.model, data.category].filter(Boolean).join(" | ")
            ) + "</p>";
        els.priceList.innerHTML = "";
        // Only the five required live retailers are shown; demo/seed retailers
        // (DemoStore, DemoElectronics, ...) never reach the user-facing table.
        var livePrices = (data.retailerPrices || []).filter(function (price) {
            return isLiveRetailer(price.retailer);
        });
        if (!livePrices.length) {
            return;
        }
        // API returns ascending by price; sort again defensively.
        var sorted = livePrices.slice().sort(function (a, b) {
            return Number(a.price) - Number(b.price);
        });
        sorted.forEach(function (price, index) {
            var isCheapest = index === 0;
            var row = document.createElement("div");
            row.className = "price-row" + (isCheapest ? " price-row-cheapest" : "");
            var rank = document.createElement("div");
            rank.className = "price-rank";
            rank.textContent = String(index + 1);
            var main = document.createElement("div");
            main.className = "price-main";
            var retailer = document.createElement("span");
            retailer.className = "retailer-name";
            retailer.textContent = price.retailer || "Unknown retailer";
            if (isCheapest) {
                var badge = document.createElement("span");
                badge.className = "badge-lowest";
                badge.textContent = "LOWEST PRICE";
                retailer.appendChild(badge);
            }
            var amount = document.createElement("div");
            amount.className = "price-amount";
            amount.textContent = formatPrice(price.price);
            var updated = document.createElement("span");
            updated.className = "price-updated";
            updated.textContent = "Last updated: " + formatUpdatedAt(price.lastUpdated);
            var actions = document.createElement("div");
            actions.className = "price-actions";
            var buy = document.createElement("a");
            buy.className = "btn btn-primary";
            buy.textContent = "Buy on " + (price.retailer || "retailer");
            buy.href = price.productUrl || "#";
            buy.target = "_blank";
            buy.rel = "noopener noreferrer";
            if (!price.productUrl) buy.setAttribute("aria-disabled", "true");
            main.appendChild(retailer);
            main.appendChild(amount);
            actions.appendChild(buy);
            row.appendChild(rank);
            row.appendChild(main);
            row.appendChild(actions);
            row.appendChild(updated);
            els.priceList.appendChild(row);
        });
    }
    /* ---------- refresh prices ---------- */
    function setRefreshLoading(loading) {
        els.refreshBtn.disabled = loading;
        els.refreshBtn.querySelector(".btn-label").textContent =
            loading ? "Refreshing..." : "Refresh Prices";
        els.refreshStatus.textContent = loading
            ? "Contacting retailers - this can take up to a minute."
            : "";
    }
    function refreshPrices() {
        if (currentProductId === null) return;
        els.refreshSummary.hidden = true;
        setRefreshLoading(true);
        apiPost("/api/products/" + currentProductId + "/refresh-prices")
            .then(function (result) {
                setRefreshLoading(false);
                renderRefreshSummary(result);
                loadComparison(currentProductId);
            })
            .catch(function (err) {
                setRefreshLoading(false);
                setStatus(els.comparisonStatus, "Refresh failed: " + err.message, "status-error");
            });
    }
    function renderRefreshSummary(result) {
        var ok = result.successfulProviders || [];
        var failed = result.failedProviders || [];
        var reasons = result.failureReasons || {}
        var box = els.refreshSummary;
        var html;
        if (!ok.length && !failed.length) {
            box.className = "refresh-summary warn";
            box.innerHTML = "<strong>No retailers are registered for refresh.</strong>";
            box.hidden = false;
            return;
        }
        if (!failed.length) {
            box.className = "refresh-summary ok";
            box.innerHTML = "<strong>Refresh completed.</strong> Providers attempted: " +
                escapeHtml(result.providersAttempted) +
                " | Succeeded: " + ok.map(escapeHtml).join(", ") +
                " | Prices saved: " + escapeHtml(result.pricesSaved);
            box.hidden = false;
            return;
        }
        html = "<strong>Refresh finished with warnings.</strong>" +
            '<ul><li>Successful: ' + (ok.length ? ok.map(escapeHtml).join(", ") : "none") + "</li>" +
            "<li>Failed providers:<ul>";
        failed.forEach(function (name) {
            html += "<li><strong>" + escapeHtml(name) + "</strong>: " +
                escapeHtml(reasons[name] || "unknown reason") + "</li>";
        });
        html += "</ul></li></ul>";
        box.className = "refresh-summary warn";
        box.innerHTML = html;
        box.hidden = false;
    }
    /* ---------- discovery & candidate confirmation (Phase 4) ---------- */
    function resetDiscover() {
        els.discoverSection.hidden = true;
        els.candidateList.innerHTML = "";
        clearStatus(els.discoverStatus);
    }
    function discoverCandidates() {
        if (currentProductId === null) return;
        els.discoverSection.hidden = false;
        els.candidateList.innerHTML = "";
        els.discoverBtn.disabled = true;
        setStatus(els.discoverStatus, "Asking retailers for matching products...", "status-info");
        apiGet("/api/products/" + currentProductId + "/discover")
            .then(function (result) {
                renderCandidates(result);
            })
            .catch(function (err) {
                setStatus(els.discoverStatus,
                    "Discovery failed: " + err.message, "status-error");
            })
            .finally(function () {
                els.discoverBtn.disabled = false;
            });
    }
    function renderCandidates(result) {
        var candidates = result.candidates || [];
        if (!candidates.length) {
            var failNote = "";
            if (result.failedProviders && result.failedProviders.length) {
                failNote = " Retailer errors: " + result.failedProviders.join(", ") + ".";
            }
            setStatus(els.discoverStatus,
                "No matching retailer products found for \"" +
                (result.searchQuery || "") + "\"." + failNote,
                "status-empty");
            return;
        }
        clearStatus(els.discoverStatus);
        els.candidateList.innerHTML = "";
        candidates.forEach(function (candidate) {
            var row = document.createElement("article");
            row.className = "candidate-row";
            var title = document.createElement("h4");
            title.className = "candidate-title";
            title.textContent = candidate.productTitle || "(untitled)";
            var meta = document.createElement("p");
            meta.className = "candidate-meta";
            meta.textContent = [
                candidate.retailer,
                candidate.sku ? "SKU: " + candidate.sku : null,
                candidate.mrp ? "MRP: " + formatPrice(candidate.mrp) : null,
                candidate.availability
            ].filter(Boolean).join(" | ");
            var link = document.createElement("a");
            link.className = "candidate-url";
            link.href = candidate.productUrl;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            link.textContent = candidate.productUrl;
            var confirmBtn = document.createElement("button");
            confirmBtn.type = "button";
            confirmBtn.className = "btn btn-primary btn-confirm";
            confirmBtn.textContent = "Use this product";
            confirmBtn.dataset.confirmRetailer = candidate.retailer || "";
            confirmBtn.dataset.confirmUrl = candidate.productUrl || "";
            confirmBtn.dataset.confirmSku = candidate.sku || "";
            confirmBtn.dataset.confirmTitle = candidate.productTitle || "";
            row.appendChild(title);
            row.appendChild(meta);
            row.appendChild(link);
            row.appendChild(confirmBtn);
            els.candidateList.appendChild(row);
        });
    }
    function confirmCandidate(button) {
        var payload = {
            retailer: button.dataset.confirmRetailer,
            productUrl: button.dataset.confirmUrl,
            sku: button.dataset.confirmSku,
            productTitle: button.dataset.confirmTitle
        }
        setStatus(els.discoverStatus, "Confirming candidate...", "status-info");
        setConfirmButtonsDisabled(true);
        apiPostJson("/api/products/" + currentProductId + "/retailer-links", payload)
            .then(function (result) {
                setStatus(els.discoverStatus,
                    result.message || ("Retailer link " + result.status + "."),
                    "status-info");
                setConfirmButtonsDisabled(false);
            })
            .catch(function (err) {
                setStatus(els.discoverStatus,
                    "Confirmation failed: " + err.message, "status-error");
                setConfirmButtonsDisabled(false);
            });
    }
    /* ---------- multi-retailer comparison (Phase 8) ---------- */
    function compareRetailers() {
        setStatus(els.multiRetailerStatus, "Fetching live retailer prices...", "status-info");
        clearStatus(els.comparisonStatus);
        els.multiRetailerResults.innerHTML = "";
        els.multiRetailerSection.hidden = false;
        setCompareRetailersButtonDisabled(true);
        apiGet("/api/products/" + currentProductId + "/multi-retailer-search")
            .then(function (response) {
                renderMultiRetailerResults(response);
                setCompareRetailersButtonDisabled(false);
            })
            .catch(function (err) {
                setStatus(els.multiRetailerStatus,
                    "Failed to load retailer prices: " + err.message, "status-error");
                setCompareRetailersButtonDisabled(false);
            });
    }
    function renderMultiRetailerResults(response) {
        var summaries = response.retailers || [];
        if (summaries.length === 0) {
            setStatus(els.multiRetailerStatus,
                "No retailer results available.", "status-empty");
            return;
        }
        setStatus(els.multiRetailerStatus,
            "Showing prices from " + summaries.length + " retailer(s).", "status-info");
        var lowestVerified = findLowestVerified(summaries);
        summaries.forEach(function (summary) {
            els.multiRetailerResults.appendChild(renderRetailerCard(summary, lowestVerified));
        });
    }
    function findLowestVerified(summaries) {
        var lowest = null;
        summaries.forEach(function (summary) {
            if (summary.status !== "VERIFIED_PRICE") return;
            (summary.offers || []).forEach(function (offer) {
                if (!offer.price) return;
                var price = Number(offer.price);
                if (isNaN(price)) return;
                if (lowest === null || price < lowest.price) {
                    lowest = { price: price, retailer: offer.retailer }
                }
            });
        });
        return lowest;
    }
    function renderRetailerCard(summary, lowestVerified) {
        var card = document.createElement("div");
        card.className = "retailer-card";
        var header = document.createElement("div");
        header.className = "retailer-card-header";
        var name = document.createElement("span");
        name.className = "retailer-card-name";
        name.textContent = summary.retailer;
        header.appendChild(name);
        var badge = document.createElement("span");
        badge.className = "status-badge status-badge-" + summary.status.toLowerCase();
        badge.textContent = statusDisplayLabel(summary.retailer, summary.status);
        header.appendChild(badge);
        card.appendChild(header);
        if (summary.message) {
            var msg = document.createElement("p");
            msg.className = "retailer-card-message";
            msg.textContent = summary.message;
            card.appendChild(msg);
        }
        (summary.offers || []).forEach(function (offer) {
            card.appendChild(renderOfferRow(offer, summary.status, lowestVerified));
        });
        return card;
    }
    function statusDisplayLabel(retailer, status) {
        if (status === "VERIFIED_PRICE") return "Verified Price";
        if (status === "MATCHED_CANDIDATES") return "Matched Candidates";
        if (status === "NO_MATCHING_PRODUCT") return "No exact match";
        if (status === "CONFIGURATION_REQUIRED") {
            return retailer === "Flipkart" ? "Official API access required" : "Official access required";
        }
        if (status === "OFFICIAL_ACCESS_REQUIRED") return "Official access required";
        if (status === "CURRENTLY_UNAVAILABLE") return "Currently unavailable";
        if (status === "PROVIDER_ERROR") return "Provider error";
        return status;
    }
    function renderOfferRow(offer, status, lowestVerified) {
        var row = document.createElement("div");
        row.className = "offer-row";
        if (offer.productTitle) {
            var title = document.createElement("p");
            title.className = "offer-title";
            title.textContent = offer.productTitle;
            row.appendChild(title);
        }
        var priceBlock = document.createElement("div");
        priceBlock.className = "offer-price-block";
        if (offer.price) {
            var price = document.createElement("span");
            price.className = "offer-price";
            price.textContent = formatPrice(offer.price);
            priceBlock.appendChild(price);
            if (status === "VERIFIED_PRICE" && lowestVerified &&
                offer.retailer === lowestVerified.retailer &&
                Number(offer.price) === lowestVerified.price) {
                var badge = document.createElement("span");
                badge.className = "badge-lowest";
                badge.textContent = "LOWEST PRICE";
                priceBlock.appendChild(badge);
            }
        }
        if (offer.mrp) {
            var mrp = document.createElement("span");
            mrp.className = "offer-mrp";
            mrp.textContent = "MRP: " + formatPrice(offer.mrp);
            priceBlock.appendChild(mrp);
        }
        row.appendChild(priceBlock);
        if (offer.availability) {
            var availability = document.createElement("span");
            availability.className = "offer-availability";
            availability.textContent = offer.availability;
            row.appendChild(availability);
        }
        if (offer.verified) {
            var verified = document.createElement("span");
            verified.className = "offer-verified";
            verified.textContent = "Verified";
            row.appendChild(verified);
        }
        // A Buy button is ONLY active for a verified offer with a real retailer
        // URL. The raw URL is never displayed as text ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â the button carries it.
        if (offer.verified && offer.productUrl) {
            var buy = document.createElement("a");
            buy.className = "btn btn-primary offer-buy";
            buy.href = offer.productUrl;
            buy.target = "_blank";
            buy.rel = "noopener noreferrer";
            buy.textContent = "Buy on " + (offer.retailer || "retailer");
            row.appendChild(buy);
        }
        return row;
    }
    function setCompareRetailersButtonDisabled(disabled) {
        els.compareRetailersBtn.disabled = disabled;
    }
    /* ---------- events ---------- */
    function bindEvents() {
        els.searchForm.addEventListener("submit", function (event) {
            event.preventDefault();
            var query = els.searchInput.value.trim();
            if (!query) {
                setStatus(els.searchStatus,
                    "Type a product name or brand to search.", "status-empty");
                els.searchResults.innerHTML = "";
                return;
            }
            runSearch(query);
        });
        els.backButton.addEventListener("click", showSearch);
        if (els.catBackButton) {
            els.catBackButton.addEventListener("click", showSearch);
        }
        if (els.detailBackButton) {
            els.detailBackButton.addEventListener("click", showSearch);
        }
        els.brandLink.addEventListener("click", function (event) {
            event.preventDefault();
            showSearch();
        });
        els.refreshBtn.addEventListener("click", refreshPrices);
        els.compareRetailersBtn.addEventListener("click", compareRetailers);
        els.discoverBtn.addEventListener("click", discoverCandidates);
        /*         * Delegated click handler: exactly ONE listener for every         * "Compare prices" button, including buttons rendered later.         * A click handler on a type="button" can never cause native         * form submission, and delegation cannot accumulate duplicates.         */
        document.addEventListener("click", function (event) {
            var confirmBtn = event.target.closest(".btn-confirm");
            if (confirmBtn) {
                event.preventDefault();
                confirmCandidate(confirmBtn);
                return;
            }
            var compareBtn = event.target.closest("[data-product-id]");
            if (compareBtn) {
                event.preventDefault();
                var id = Number(compareBtn.getAttribute("data-product-id"));
                if (!isNaN(id)) openComparison(id);
                return;
            }
            // Safety net: any submit-type button outside the search form
            // must never trigger native submission/navigation.
            var btn = event.target.closest("button");
            if (btn && btn.type === "submit" &&
                    els.searchForm &&
                    !els.searchForm.contains(btn)) {
                event.preventDefault();
            }
        });
        /*         * Belt-and-braces: no <form> in this single-page UI needs native         * submission. Blocking submit at capture time makes a page         * reset/navigation from accidental submission impossible,         * regardless of markup changes.         */
        document.addEventListener("submit", function (event) {
            event.preventDefault();
        }, true);
    }
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", function () {
            bindEvents();
            console.log("[PriceComparison] app.js " + APP_VERSION + " ready");
        });
    } else {
        bindEvents();
        console.log("[PriceComparison] app.js " + APP_VERSION + " ready");
    }
})();
