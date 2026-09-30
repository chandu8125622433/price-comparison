import { test, expect } from '@playwright/test';

test('search, compare and refresh prices keeps comparison visible', async ({ page }) => {
    await page.goto('/');
    const searchInput = page.getByLabel('Product search');
    await searchInput.fill('SAMSUNG TV');
    await page.locator('#search-button').click();
    const samsungCard = page.locator('.product-card').filter({ hasText: 'Samsung 55 inch 4K Smart TV' });
    await expect(samsungCard).toBeVisible();
    await samsungCard.getByRole('button', { name: 'Compare prices' }).click();
    const compareSection = page.locator('#compare-section');
    await expect(compareSection).toBeVisible();
    await expect(compareSection.locator('#product-header h2')).toHaveText('Samsung 55 inch 4K Smart TV');
    await page.route('**/api/products/*/refresh-prices', async (route) => {
        if (route.request().method() !== 'POST') { await route.continue(); return; }
        await route.fulfill({
            status: 200, contentType: 'application/json',
            body: JSON.stringify({
                productId: 1, providersAttempted: 1,
                successfulProviders: ['Vijay Sales'], failedProviders: [],
                pricesSaved: 1, failureReasons: {},
                providerResults: { 'Vijay Sales': { status: 'OK', message: 'Refresh completed: 1 price(s) saved' } }
            })
        });
    });
    await compareSection.getByRole('button', { name: 'Refresh Prices' }).click();
    const refreshSummary = compareSection.locator('#refresh-summary');
    await expect(refreshSummary).toBeVisible();
    await expect(refreshSummary).toContainText('Refresh completed');
    await expect(refreshSummary).toContainText('Vijay Sales');
    await expect(compareSection).toBeVisible();
});

test('multi-retailer comparison shows retailer statuses and verified offers', async ({ page }) => {
    await page.route('**/api/products/*/multi-retailer-search', async (route) => {
        if (route.request().method() !== 'GET') { await route.continue(); return; }
        await route.fulfill({
            status: 200, contentType: 'application/json',
            body: JSON.stringify({
                query: 'Samsung TV', decision: 'MULTI_RETAILER',
                retailers: [
                    { retailer: 'Vijay Sales', status: 'VERIFIED_PRICE', message: 'Verified price', offers: [{ retailer: 'Vijay Sales', sku: '256812', title: 'Samsung TV', price: '18000', currency: 'INR', availability: 'In Stock', productUrl: 'https://www.vijaysales.com/p/123', verified: true }] },
                    { retailer: 'Reliance Digital', status: 'NO_MATCHING_PRODUCT', message: 'No exact match', offers: [] },
                    { retailer: 'Flipkart', status: 'CONFIGURATION_REQUIRED', message: 'Official API access required', offers: [] },
                    { retailer: 'Amazon', status: 'OFFICIAL_ACCESS_REQUIRED', message: 'Official access required', offers: [] },
                    { retailer: 'Croma', status: 'CURRENTLY_UNAVAILABLE', message: 'Currently unavailable', offers: [] }
                ],
                lowestVerifiedPrice: '18000'
            })
        });
    });
    await page.goto('/');
    await page.getByLabel('Product search').fill('Samsung TV');
    await page.locator('#search-button').click();
    const samsungCard = page.locator('.product-card').filter({ hasText: 'Samsung 55 inch 4K Smart TV' });
    await expect(samsungCard).toBeVisible();
    await samsungCard.getByRole('button', { name: 'Compare prices' }).click();
    const compareSection = page.locator('#compare-section');
    await expect(compareSection).toBeVisible();
    await compareSection.getByRole('button', { name: 'Compare Retailers' }).click();
    const multiRetailerSection = compareSection.locator('#multi-retailer-section');
    await expect(multiRetailerSection).toBeVisible();
    const retailerCards = multiRetailerSection.locator('.retailer-card');
    await expect(retailerCards).toHaveCount(5);
    const vijayCard = retailerCards.filter({ hasText: 'Vijay Sales' });
    await expect(vijayCard.locator('.status-badge')).toContainText('Verified Price');
    await expect(vijayCard.locator('.offer-price')).toContainText('18,000');
    await expect(vijayCard.locator('.offer-buy')).toBeVisible();
    await expect(vijayCard.locator('.offer-buy')).toHaveAttribute('href', /vijaysales\.com/);
    await expect(vijayCard.locator('.offer-buy')).toContainText('Buy on Vijay Sales');
    const rdCard = retailerCards.filter({ hasText: 'Reliance Digital' });
    await expect(rdCard.locator('.status-badge')).toContainText('No exact match');
    await expect(rdCard.locator('.offer-price')).toHaveCount(0);
    await expect(rdCard.locator('.offer-buy')).toHaveCount(0);
    const flipkartCard = retailerCards.filter({ hasText: 'Flipkart' });
    await expect(flipkartCard.locator('.status-badge')).toContainText('Official API access required');
    await expect(flipkartCard.locator('.offer-buy')).toHaveCount(0);
    const amazonCard = retailerCards.filter({ hasText: 'Amazon' });
    await expect(amazonCard.locator('.status-badge')).toContainText('Official access required');
    const cromaCard = retailerCards.filter({ hasText: 'Croma' });
    await expect(cromaCard.locator('.status-badge')).toContainText('Currently unavailable');
    const cardCount = await retailerCards.count();
    for (let i = 0; i < cardCount; i++) {
      await expect(retailerCards.nth(i)).not.toContainText('https://');
    }
    await expect(multiRetailerSection).not.toContainText('DemoElectronics');
    await expect(multiRetailerSection).not.toContainText('DemoStore');
});

test('category search mobiles shows a product listing not a single product', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel('Product search').fill('mobiles');
    await page.locator('#search-button').click();
    const categorySection = page.locator('#category-section');
    await expect(categorySection).toBeVisible();
    const cards = categorySection.locator('.category-card');
    expect(await cards.count()).toBeGreaterThan(5);
    const firstCard = cards.first();
    await expect(firstCard.locator('.card-name')).toBeVisible();
    await expect(firstCard.getByRole('button', { name: 'Compare prices' })).toBeVisible();
    await expect(categorySection).not.toContainText('Extended Warranty');
    await expect(categorySection).not.toContainText('Protection Plan');
    await page.locator('#cat-back-button').click();
    await expect(page.locator('#search-section')).toBeVisible();
});

test('category search renders a listing for headphones', async ({ page }) => {
    // "headphones" is a pure category alias with no exact DB product match,
    // so it always yields the multi-product category listing.
    await page.goto('/');
    await page.getByLabel('Product search').fill('headphones');
    await page.locator('#search-button').click();
    await expect(page.locator('#category-section')).toBeVisible();
    expect(await page.locator('#category-section .category-card').count()).toBeGreaterThan(0);
});
