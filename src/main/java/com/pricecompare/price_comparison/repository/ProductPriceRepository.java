package com.pricecompare.price_comparison.repository;

import com.pricecompare.price_comparison.entity.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, Long> {

    List<ProductPrice> findByProductId(Long productId);

    List<ProductPrice> findByProductIdOrderByPriceAsc(Long productId);

    Optional<ProductPrice> findByProductIdAndWebsite(Long productId, String website);

    boolean existsByProductId(Long productId);

    Optional<ProductPrice> findFirstByProductIdOrderByPriceAsc(Long productId);
}
