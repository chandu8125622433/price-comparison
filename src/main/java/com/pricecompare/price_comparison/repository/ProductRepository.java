package com.pricecompare.price_comparison.repository;

import com.pricecompare.price_comparison.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsByNameAndBrandAndModelAndCategory(
            String name,
            String brand,
            String model,
            String category
    );

    Optional<Product> findByNameAndBrandAndModelAndCategory(
            String name,
            String brand,
            String model,
            String category
    );

    List<Product> findByNameContainingIgnoreCaseOrBrandContainingIgnoreCaseOrModelContainingIgnoreCaseOrCategoryContainingIgnoreCase(
            String name,
            String brand,
            String model,
            String category
    );
}
