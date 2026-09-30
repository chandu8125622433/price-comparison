package com.pricecompare.price_comparison.service;

import com.pricecompare.price_comparison.dto.PriceInfo;
import com.pricecompare.price_comparison.dto.ProductComparisonResponse;
import com.pricecompare.price_comparison.retailer.RetailerKey;
import com.pricecompare.price_comparison.dto.ProductDetailResponse;
import com.pricecompare.price_comparison.entity.Product;
import com.pricecompare.price_comparison.exception.ResourceNotFoundException;
import com.pricecompare.price_comparison.repository.ProductPriceRepository;
import com.pricecompare.price_comparison.repository.ProductRepository;
import com.pricecompare.price_comparison.repository.RetailerLinkRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductPriceRepository productPriceRepository;
    private final RetailerLinkRepository retailerLinkRepository;

    public ProductService(ProductRepository productRepository,
                          ProductPriceRepository productPriceRepository,
                          RetailerLinkRepository retailerLinkRepository) {
        this.productRepository = productRepository;
        this.productPriceRepository = productPriceRepository;
        this.retailerLinkRepository = retailerLinkRepository;
    }

    public List<Product> searchProducts(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String[] terms = query.trim().toLowerCase().split("\\s+");
        Specification<Product> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (String term : terms) {
                String likePattern = "%" + term + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), likePattern),
                        cb.like(cb.lower(root.get("brand")), likePattern),
                        cb.like(cb.lower(root.get("model")), likePattern),
                        cb.like(cb.lower(root.get("category")), likePattern)
                ));
            }
            cq.distinct(true);
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return productRepository.findAll(spec);
    }

    public ProductDetailResponse getProductDetail(Long productId) {
        Product product = requireProduct(productId);
        return ProductDetailResponse.from(product, loadPrices(productId));
    }

    public ProductComparisonResponse getProductComparison(Long productId) {
        Product product = requireProduct(productId);
        return ProductComparisonResponse.from(product, loadPrices(productId));
    }

    public Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Product not found with id: " + productId));
    }

    private List<PriceInfo> loadPrices(Long productId) {
        return productPriceRepository.findByProductIdOrderByPriceAsc(productId)
                .stream()
                .filter(row -> RetailerKey.hasOfficialProductUrl(row.getWebsite(), row.getProductUrl()))
                .filter(row -> retailerLinkRepository.findByProductIdAndWebsite(productId, row.getWebsite())
                        .filter(link -> link.getSku() != null && row.getSku() != null
                                && link.getSku().equalsIgnoreCase(row.getSku())
                                && link.getProductUrl().equals(row.getProductUrl()))
                        .isPresent())
                .map(PriceInfo::from)
                .toList();
    }
}
