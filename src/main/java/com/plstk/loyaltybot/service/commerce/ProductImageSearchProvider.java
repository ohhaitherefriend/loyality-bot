package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.entity.commerce.ImageCandidate;
import com.plstk.loyaltybot.entity.commerce.Product;

import java.util.List;

public interface ProductImageSearchProvider {

    List<ImageCandidate> search(Product product);
}
